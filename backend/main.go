package main

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	defaultDataDir         = "/var/data/drops"
	defaultDropTTL         = 24 * time.Hour
	defaultMaxDropBytes    = int64(10 * 1024 * 1024 * 1024)
	defaultMaxStorageBytes = int64(9 * 1024 * 1024 * 1024)
	maxUploadChunk         = int64(1024 * 1024)
)

type dropMetadata struct {
	ID                string    `json:"id"`
	FileName          string    `json:"file_name"`
	OriginalSize      int64     `json:"original_size"`
	PackageSize       int64     `json:"package_size"`
	Offset            int64     `json:"offset"`
	CreatedAt         time.Time `json:"created_at"`
	ExpiresAt         time.Time `json:"expires_at"`
	State             string    `json:"state"`
	UploadTokenHash   string    `json:"upload_token_hash"`
	DownloadTokenHash string    `json:"download_token_hash"`
}

type createDropRequest struct {
	FileName     string `json:"file_name"`
	OriginalSize int64  `json:"original_size"`
	PackageSize  int64  `json:"package_size"`
}

type createDropResponse struct {
	ID            string `json:"id"`
	UploadToken   string `json:"upload_token"`
	DownloadToken string `json:"download_token"`
	DownloadPath  string `json:"download_path"`
	ExpiresAt     string `json:"expires_at"`
}

type dropStore struct {
	dir   string
	locks sync.Map
}

func newDropStore(dir string) (*dropStore, error) {
	if dir == "" {
		dir = defaultDataDir
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	return &dropStore{dir: dir}, nil
}

func (s *dropStore) lock(id string) *sync.Mutex {
	value, _ := s.locks.LoadOrStore(id, &sync.Mutex{})
	return value.(*sync.Mutex)
}

func (s *dropStore) metadataPath(id string) string {
	return filepath.Join(s.dir, id+".json")
}

func (s *dropStore) partPath(id string) string {
	return filepath.Join(s.dir, id+".part")
}

func (s *dropStore) blobPath(id string) string {
	return filepath.Join(s.dir, id+".blob")
}

func (s *dropStore) load(id string) (dropMetadata, error) {
	var metadata dropMetadata
	data, err := os.ReadFile(s.metadataPath(id))
	if err != nil {
		return metadata, err
	}
	if err := json.Unmarshal(data, &metadata); err != nil {
		return metadata, err
	}
	if metadata.ID != id {
		return metadata, errors.New("drop metadata id mismatch")
	}
	return metadata, nil
}

func (s *dropStore) save(metadata dropMetadata) error {
	data, err := json.Marshal(metadata)
	if err != nil {
		return err
	}
	temporary, err := os.CreateTemp(s.dir, metadata.ID+".json-")
	if err != nil {
		return err
	}
	temporaryName := temporary.Name()
	defer os.Remove(temporaryName)
	if err := temporary.Chmod(0600); err != nil {
		temporary.Close()
		return err
	}
	if _, err := temporary.Write(data); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	return os.Rename(temporaryName, s.metadataPath(metadata.ID))
}

func (s *dropStore) remove(id string) error {
	var firstErr error
	for _, path := range []string{s.metadataPath(id), s.partPath(id), s.blobPath(id)} {
		if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}

func (s *dropStore) usageBytes() (int64, error) {
	entries, err := os.ReadDir(s.dir)
	if err != nil {
		return 0, err
	}
	var total int64
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			return 0, err
		}
		total += info.Size()
	}
	return total, nil
}

func (s *dropStore) cleanupExpired(now time.Time) error {
	entries, err := os.ReadDir(s.dir)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if !strings.HasSuffix(entry.Name(), ".json") {
			continue
		}
		id := strings.TrimSuffix(entry.Name(), ".json")
		if !validDropID(id) {
			continue
		}
		lock := s.lock(id)
		lock.Lock()
		metadata, loadErr := s.load(id)
		if loadErr == nil && now.Before(metadata.ExpiresAt) {
			lock.Unlock()
			continue
		}
		_ = s.remove(id)
		lock.Unlock()
	}
	return nil
}

type apiServer struct {
	store           *dropStore
	ttl             time.Duration
	maxDropBytes    int64
	maxStorageBytes int64
	storageMu       sync.Mutex
	cleanupStop     chan struct{}
	cleanupClosed   chan struct{}
}

func newAPIServer(store *dropStore, ttl time.Duration, maxDropBytes, maxStorageBytes int64) *apiServer {
	if ttl <= 0 {
		ttl = defaultDropTTL
	}
	if maxDropBytes <= 0 {
		maxDropBytes = defaultMaxDropBytes
	}
	if maxStorageBytes <= 0 {
		maxStorageBytes = defaultMaxStorageBytes
	}
	server := &apiServer{
		store:           store,
		ttl:             ttl,
		maxDropBytes:    maxDropBytes,
		maxStorageBytes: maxStorageBytes,
		cleanupStop:     make(chan struct{}),
		cleanupClosed:   make(chan struct{}),
	}
	go server.cleanupLoop()
	return server
}

func (s *apiServer) close() {
	select {
	case <-s.cleanupStop:
		return
	default:
		close(s.cleanupStop)
		<-s.cleanupClosed
	}
}

func (s *apiServer) cleanupLoop() {
	ticker := time.NewTicker(15 * time.Minute)
	defer func() {
		ticker.Stop()
		close(s.cleanupClosed)
	}()
	for {
		select {
		case <-ticker.C:
			_ = s.store.cleanupExpired(time.Now().UTC())
		case <-s.cleanupStop:
			return
		}
	}
}

func (s *apiServer) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	if request.URL.Path == "/healthz" {
		if request.Method != http.MethodGet {
			writer.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		writer.WriteHeader(http.StatusNoContent)
		return
	}
	if request.URL.Path == "/v1/drops" {
		if request.Method == http.MethodPost {
			s.createDrop(writer, request)
			return
		}
		writer.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	const prefix = "/v1/drops/"
	if !strings.HasPrefix(request.URL.Path, prefix) {
		writer.WriteHeader(http.StatusNotFound)
		return
	}
	pathParts := strings.Split(strings.TrimPrefix(request.URL.Path, prefix), "/")
	if len(pathParts) != 2 || pathParts[1] != "content" || !validDropID(pathParts[0]) {
		writer.WriteHeader(http.StatusNotFound)
		return
	}
	s.handleContent(writer, request, pathParts[0])
}

func (s *apiServer) createDrop(writer http.ResponseWriter, request *http.Request) {
	request.Body = http.MaxBytesReader(writer, request.Body, 64*1024)
	var input createDropRequest
	decoder := json.NewDecoder(request.Body)
	if err := decoder.Decode(&input); err != nil {
		writeError(writer, http.StatusBadRequest, "invalid request")
		return
	}
	if input.PackageSize <= 0 || input.PackageSize > s.maxDropBytes || input.OriginalSize < 0 || input.OriginalSize > s.maxDropBytes {
		writeError(writer, http.StatusRequestEntityTooLarge, "invalid size")
		return
	}
	fileName := sanitizeFileName(input.FileName)
	id, err := randomHex(16)
	if err != nil {
		writeError(writer, http.StatusInternalServerError, "unable to create drop")
		return
	}
	uploadToken, err := randomToken(32)
	if err != nil {
		writeError(writer, http.StatusInternalServerError, "unable to create drop")
		return
	}
	downloadToken, err := randomToken(32)
	if err != nil {
		writeError(writer, http.StatusInternalServerError, "unable to create drop")
		return
	}
	now := time.Now().UTC()
	metadata := dropMetadata{
		ID:                id,
		FileName:          fileName,
		OriginalSize:      input.OriginalSize,
		PackageSize:       input.PackageSize,
		CreatedAt:         now,
		ExpiresAt:         now.Add(s.ttl),
		State:             "uploading",
		UploadTokenHash:   hashToken(uploadToken),
		DownloadTokenHash: hashToken(downloadToken),
	}
	if err := s.store.save(metadata); err != nil {
		writeError(writer, http.StatusInternalServerError, "unable to create drop")
		return
	}
	response := createDropResponse{
		ID:            id,
		UploadToken:   uploadToken,
		DownloadToken: downloadToken,
		DownloadPath:  "/v1/drops/" + id + "/content",
		ExpiresAt:     metadata.ExpiresAt.Format(time.RFC3339),
	}
	writeJSON(writer, http.StatusCreated, response)
}

func (s *apiServer) handleContent(writer http.ResponseWriter, request *http.Request, id string) {
	lock := s.store.lock(id)
	lock.Lock()
	defer lock.Unlock()
	metadata, err := s.store.load(id)
	if errors.Is(err, os.ErrNotExist) {
		writer.WriteHeader(http.StatusNotFound)
		return
	}
	if err != nil {
		writeError(writer, http.StatusInternalServerError, "invalid drop")
		return
	}
	if !time.Now().UTC().Before(metadata.ExpiresAt) {
		_ = s.store.remove(id)
		writer.WriteHeader(http.StatusGone)
		return
	}
	if metadata.State == "uploading" {
		if info, statErr := os.Stat(s.store.blobPath(id)); statErr == nil && info.Size() == metadata.PackageSize {
			metadata.Offset = metadata.PackageSize
			metadata.State = "ready"
			if saveErr := s.store.save(metadata); saveErr != nil {
				writeError(writer, http.StatusInternalServerError, "unable to recover drop")
				return
			}
		}
	}
	switch request.Method {
	case http.MethodHead:
		s.headContent(writer, request, metadata)
	case http.MethodPut:
		s.uploadContent(writer, request, metadata)
	case http.MethodGet:
		s.downloadContent(writer, request, metadata)
	case http.MethodDelete:
		s.deleteContent(writer, request, metadata)
	default:
		writer.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (s *apiServer) headContent(writer http.ResponseWriter, request *http.Request, metadata dropMetadata) {
	if authorize(request, metadata.UploadTokenHash) {
		setDropHeaders(writer, metadata)
		writer.Header().Set("X-Upload-Offset", strconv.FormatInt(metadata.Offset, 10))
		writer.WriteHeader(http.StatusOK)
		return
	}
	if authorize(request, metadata.DownloadTokenHash) {
		if metadata.State != "ready" {
			writer.WriteHeader(http.StatusConflict)
			return
		}
		setDropHeaders(writer, metadata)
		writer.Header().Set("Content-Length", strconv.FormatInt(metadata.PackageSize, 10))
		writer.WriteHeader(http.StatusOK)
		return
	}
	writer.WriteHeader(http.StatusUnauthorized)
}

func (s *apiServer) uploadContent(writer http.ResponseWriter, request *http.Request, metadata dropMetadata) {
	if !authorize(request, metadata.UploadTokenHash) {
		writer.WriteHeader(http.StatusUnauthorized)
		return
	}
	if metadata.State == "ready" {
		writer.Header().Set("X-Upload-Offset", strconv.FormatInt(metadata.PackageSize, 10))
		writer.WriteHeader(http.StatusConflict)
		return
	}
	start, end, total, err := parseContentRange(request.Header.Get("Content-Range"))
	chunkLength := end - start + 1
	if err != nil || total != metadata.PackageSize || start != metadata.Offset || chunkLength <= 0 || chunkLength > maxUploadChunk || request.ContentLength != chunkLength {
		writer.Header().Set("X-Upload-Offset", strconv.FormatInt(metadata.Offset, 10))
		writer.WriteHeader(http.StatusConflict)
		return
	}
	s.storageMu.Lock()
	defer s.storageMu.Unlock()
	partSize := int64(0)
	if info, statErr := os.Stat(s.store.partPath(metadata.ID)); statErr == nil {
		partSize = info.Size()
	} else if !errors.Is(statErr, os.ErrNotExist) {
		writeError(writer, http.StatusInternalServerError, "unable to inspect drop storage")
		return
	}
	usage, usageErr := s.store.usageBytes()
	if usageErr != nil {
		writeError(writer, http.StatusInternalServerError, "unable to inspect drop storage")
		return
	}
	nextPartSize := end + 1
	if nextPartSize < partSize {
		nextPartSize = partSize
	}
	if usage-partSize+nextPartSize > s.maxStorageBytes {
		writer.WriteHeader(http.StatusInsufficientStorage)
		return
	}
	file, err := os.OpenFile(s.store.partPath(metadata.ID), os.O_CREATE|os.O_WRONLY, 0600)
	if err != nil {
		writeError(writer, http.StatusInternalServerError, "unable to store drop")
		return
	}
	if _, err := file.Seek(start, io.SeekStart); err != nil {
		file.Close()
		writeError(writer, http.StatusInternalServerError, "unable to store drop")
		return
	}
	written, copyErr := io.CopyN(file, request.Body, chunkLength)
	if copyErr == nil {
		copyErr = file.Sync()
	}
	closeErr := file.Close()
	if copyErr != nil || closeErr != nil || written != chunkLength {
		writer.Header().Set("X-Upload-Offset", strconv.FormatInt(metadata.Offset, 10))
		writer.WriteHeader(http.StatusBadRequest)
		return
	}
	metadata.Offset = end + 1
	if metadata.Offset == metadata.PackageSize {
		if err := os.Rename(s.store.partPath(metadata.ID), s.store.blobPath(metadata.ID)); err != nil {
			writeError(writer, http.StatusInternalServerError, "unable to finalize drop")
			return
		}
		metadata.State = "ready"
	}
	if err := s.store.save(metadata); err != nil {
		writeError(writer, http.StatusInternalServerError, "unable to save drop")
		return
	}
	writer.Header().Set("X-Upload-Offset", strconv.FormatInt(metadata.Offset, 10))
	writer.WriteHeader(http.StatusNoContent)
}

func (s *apiServer) downloadContent(writer http.ResponseWriter, request *http.Request, metadata dropMetadata) {
	if !authorize(request, metadata.DownloadTokenHash) || metadata.State != "ready" {
		writer.WriteHeader(http.StatusUnauthorized)
		return
	}
	file, err := os.Open(s.store.blobPath(metadata.ID))
	if errors.Is(err, os.ErrNotExist) {
		writer.WriteHeader(http.StatusNotFound)
		return
	}
	if err != nil {
		writeError(writer, http.StatusInternalServerError, "unable to read drop")
		return
	}
	defer file.Close()
	start, end, partial, err := requestedRange(request.Header.Get("Range"), metadata.PackageSize)
	if err != nil {
		writer.Header().Set("Content-Range", "bytes */"+strconv.FormatInt(metadata.PackageSize, 10))
		writer.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
		return
	}
	if _, err := file.Seek(start, io.SeekStart); err != nil {
		writeError(writer, http.StatusInternalServerError, "unable to read drop")
		return
	}
	setDropHeaders(writer, metadata)
	writer.Header().Set("Accept-Ranges", "bytes")
	writer.Header().Set("Content-Type", "application/octet-stream")
	writer.Header().Set("Content-Length", strconv.FormatInt(end-start+1, 10))
	if partial {
		writer.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, metadata.PackageSize))
		writer.WriteHeader(http.StatusPartialContent)
	} else {
		writer.WriteHeader(http.StatusOK)
	}
	_, _ = io.CopyN(writer, file, end-start+1)
}

func (s *apiServer) deleteContent(writer http.ResponseWriter, request *http.Request, metadata dropMetadata) {
	if !authorize(request, metadata.UploadTokenHash) && !authorize(request, metadata.DownloadTokenHash) {
		writer.WriteHeader(http.StatusUnauthorized)
		return
	}
	if err := s.store.remove(metadata.ID); err != nil {
		writeError(writer, http.StatusInternalServerError, "unable to delete drop")
		return
	}
	writer.WriteHeader(http.StatusNoContent)
}

func setDropHeaders(writer http.ResponseWriter, metadata dropMetadata) {
	writer.Header().Set("Cache-Control", "no-store")
	writer.Header().Set("X-DeadDrop-Name", base64.RawURLEncoding.EncodeToString([]byte(metadata.FileName)))
	writer.Header().Set("X-DeadDrop-Original-Size", strconv.FormatInt(metadata.OriginalSize, 10))
	writer.Header().Set("X-DeadDrop-Package-Size", strconv.FormatInt(metadata.PackageSize, 10))
	writer.Header().Set("X-DeadDrop-Expires", metadata.ExpiresAt.Format(time.RFC3339))
}

func authorize(request *http.Request, expectedHash string) bool {
	token := ""
	if value := request.Header.Get("Authorization"); strings.HasPrefix(value, "Bearer ") {
		token = strings.TrimSpace(strings.TrimPrefix(value, "Bearer "))
	}
	if token == "" {
		token = request.URL.Query().Get("token")
	}
	if token == "" || expectedHash == "" {
		return false
	}
	actual := hashToken(token)
	return subtleConstantTimeEqual(actual, expectedHash)
}

func subtleConstantTimeEqual(left, right string) bool {
	if len(left) != len(right) {
		return false
	}
	var result byte
	for index := range left {
		result |= left[index] ^ right[index]
	}
	return result == 0
}

func parseContentRange(value string) (int64, int64, int64, error) {
	parts := strings.Fields(value)
	if len(parts) != 2 || parts[0] != "bytes" {
		return 0, 0, 0, errors.New("invalid content range")
	}
	rangeAndTotal := strings.Split(parts[1], "/")
	if len(rangeAndTotal) != 2 {
		return 0, 0, 0, errors.New("invalid content range")
	}
	byteRange := strings.Split(rangeAndTotal[0], "-")
	if len(byteRange) != 2 {
		return 0, 0, 0, errors.New("invalid content range")
	}
	start, startErr := strconv.ParseInt(byteRange[0], 10, 64)
	end, endErr := strconv.ParseInt(byteRange[1], 10, 64)
	total, totalErr := strconv.ParseInt(rangeAndTotal[1], 10, 64)
	if startErr != nil || endErr != nil || totalErr != nil || start < 0 || end < start || total <= end {
		return 0, 0, 0, errors.New("invalid content range")
	}
	return start, end, total, nil
}

func requestedRange(value string, size int64) (int64, int64, bool, error) {
	if value == "" {
		return 0, size - 1, false, nil
	}
	parts := strings.Split(value, "=")
	if len(parts) != 2 || parts[0] != "bytes" || strings.Contains(parts[1], ",") {
		return 0, 0, false, errors.New("invalid range")
	}
	byteRange := strings.Split(parts[1], "-")
	if len(byteRange) != 2 {
		return 0, 0, false, errors.New("invalid range")
	}
	if byteRange[0] == "" {
		length, err := strconv.ParseInt(byteRange[1], 10, 64)
		if err != nil || length <= 0 {
			return 0, 0, false, errors.New("invalid range")
		}
		if length > size {
			length = size
		}
		return size - length, size - 1, true, nil
	}
	start, err := strconv.ParseInt(byteRange[0], 10, 64)
	if err != nil || start < 0 || start >= size {
		return 0, 0, false, errors.New("invalid range")
	}
	end := size - 1
	if byteRange[1] != "" {
		end, err = strconv.ParseInt(byteRange[1], 10, 64)
		if err != nil || end < start {
			return 0, 0, false, errors.New("invalid range")
		}
		if end >= size {
			end = size - 1
		}
	}
	return start, end, true, nil
}

func sanitizeFileName(value string) string {
	value = strings.TrimSpace(value)
	var builder strings.Builder
	for _, character := range value {
		if character < 32 || character == 127 || character == '/' || character == '\\' {
			builder.WriteRune('_')
			continue
		}
		builder.WriteRune(character)
		if builder.Len() >= 180 {
			break
		}
	}
	value = strings.Trim(builder.String(), " .")
	value = strings.TrimLeft(value, ".")
	if value == "" {
		return "unnamed-file"
	}
	return value
}

func randomHex(size int) (string, error) {
	data := make([]byte, size)
	if _, err := rand.Read(data); err != nil {
		return "", err
	}
	return hex.EncodeToString(data), nil
}

func randomToken(size int) (string, error) {
	data := make([]byte, size)
	if _, err := rand.Read(data); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(data), nil
}

func hashToken(token string) string {
	digest := sha256.Sum256([]byte(token))
	return hex.EncodeToString(digest[:])
}

func validDropID(value string) bool {
	if len(value) != 32 {
		return false
	}
	for _, character := range value {
		if !((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f')) {
			return false
		}
	}
	return true
}

func writeJSON(writer http.ResponseWriter, status int, value interface{}) {
	writer.Header().Set("Content-Type", "application/json")
	writer.Header().Set("Cache-Control", "no-store")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(value)
}

func writeError(writer http.ResponseWriter, status int, message string) {
	writeJSON(writer, status, map[string]string{"error": message})
}

func envDuration(name string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

func envInt64(name string, fallback int64) int64 {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

func main() {
	store, err := newDropStore(os.Getenv("DATA_DIR"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "unable to initialize drop storage")
		return
	}
	_ = store.cleanupExpired(time.Now().UTC())
	server := newAPIServer(store, envDuration("DROP_TTL", defaultDropTTL), envInt64("MAX_DROP_BYTES", defaultMaxDropBytes), envInt64("MAX_STORAGE_BYTES", defaultMaxStorageBytes))
	defer server.close()
	port := os.Getenv("PORT")
	if port == "" {
		port = "10000"
	}
	if err := http.ListenAndServe(":"+port, server); err != nil {
		fmt.Fprintln(os.Stderr, "server stopped")
	}
}
