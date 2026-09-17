package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)

func testAPI(t *testing.T) (*apiServer, *httptest.Server) {
	t.Helper()
	store, err := newDropStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	server := newAPIServer(store, time.Hour, 1024*1024, defaultMaxStorageBytes)
	httpServer := httptest.NewServer(server)
	t.Cleanup(func() {
		httpServer.Close()
		server.close()
	})
	return server, httpServer
}

func createTestDrop(t *testing.T, baseURL string, packageSize int64) createDropResponse {
	t.Helper()
	body, err := json.Marshal(createDropRequest{FileName: "../private.jpg", OriginalSize: packageSize, PackageSize: packageSize})
	if err != nil {
		t.Fatal(err)
	}
	response, err := http.Post(baseURL+"/v1/drops", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusCreated {
		t.Fatalf("create status: %d", response.StatusCode)
	}
	var result createDropResponse
	if err := json.NewDecoder(response.Body).Decode(&result); err != nil {
		t.Fatal(err)
	}
	return result
}

func uploadTestChunk(t *testing.T, url, token string, offset int64, total int64, payload []byte) *http.Response {
	t.Helper()
	request, err := http.NewRequest(http.MethodPut, url, bytes.NewReader(payload))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Content-Range", "bytes "+strconv.FormatInt(offset, 10)+"-"+strconv.FormatInt(offset+int64(len(payload))-1, 10)+"/"+strconv.FormatInt(total, 10))
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	return response
}

func TestUploadResumeRangeAndAuth(t *testing.T) {
	_, httpServer := testAPI(t)
	payload := []byte("encrypted package payload")
	drop := createTestDrop(t, httpServer.URL, int64(len(payload)))
	endpoint := httpServer.URL + drop.DownloadPath

	response := uploadTestChunk(t, endpoint, drop.UploadToken, 0, int64(len(payload)), payload[:8])
	if response.StatusCode != http.StatusNoContent || response.Header.Get("X-Upload-Offset") != "8" {
		t.Fatalf("first upload: status=%d offset=%q", response.StatusCode, response.Header.Get("X-Upload-Offset"))
	}
	response.Body.Close()

	request, err := http.NewRequest(http.MethodHead, endpoint, nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+drop.UploadToken)
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK || response.Header.Get("X-Upload-Offset") != "8" {
		t.Fatalf("resume offset: status=%d offset=%q", response.StatusCode, response.Header.Get("X-Upload-Offset"))
	}
	response.Body.Close()

	response = uploadTestChunk(t, endpoint, drop.UploadToken, 0, int64(len(payload)), payload)
	if response.StatusCode != http.StatusConflict || response.Header.Get("X-Upload-Offset") != "8" {
		t.Fatalf("stale upload: status=%d offset=%q", response.StatusCode, response.Header.Get("X-Upload-Offset"))
	}
	response.Body.Close()

	response = uploadTestChunk(t, endpoint, drop.UploadToken, 8, int64(len(payload)), payload[8:])
	if response.StatusCode != http.StatusNoContent || response.Header.Get("X-Upload-Offset") != strconv.Itoa(len(payload)) {
		t.Fatalf("final upload: status=%d offset=%q", response.StatusCode, response.Header.Get("X-Upload-Offset"))
	}
	response.Body.Close()

	request, err = http.NewRequest(http.MethodGet, endpoint+"?token="+drop.DownloadToken, nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Range", "bytes=2-7")
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	data, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusPartialContent || string(data) != string(payload[2:8]) {
		t.Fatalf("range response: status=%d body=%q", response.StatusCode, data)
	}

	request, err = http.NewRequest(http.MethodGet, endpoint+"?token=wrong", nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("invalid token status: %d", response.StatusCode)
	}
}

func TestExpiredDropCleanup(t *testing.T) {
	server, httpServer := testAPI(t)
	drop := createTestDrop(t, httpServer.URL, 4)
	metadata, err := server.store.load(drop.ID)
	if err != nil {
		t.Fatal(err)
	}
	metadata.ExpiresAt = time.Now().UTC().Add(-time.Minute)
	if err := server.store.save(metadata); err != nil {
		t.Fatal(err)
	}
	if err := server.store.cleanupExpired(time.Now().UTC()); err != nil {
		t.Fatal(err)
	}
	for _, suffix := range []string{".json", ".part", ".blob"} {
		if _, err := os.Stat(filepath.Join(server.store.dir, drop.ID+suffix)); !os.IsNotExist(err) {
			t.Fatalf("expired file remains: %s", suffix)
		}
	}
}

func TestDeleteRequiresTokenAndCleansFiles(t *testing.T) {
	server, httpServer := testAPI(t)
	drop := createTestDrop(t, httpServer.URL, 4)
	endpoint := httpServer.URL + drop.DownloadPath

	request, err := http.NewRequest(http.MethodDelete, endpoint+"?token=invalid", nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("invalid delete status: %d", response.StatusCode)
	}

	request, err = http.NewRequest(http.MethodDelete, endpoint, nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+drop.DownloadToken)
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusNoContent {
		t.Fatalf("delete status: %d", response.StatusCode)
	}
	if _, err := os.Stat(filepath.Join(server.store.dir, drop.ID+".json")); !os.IsNotExist(err) {
		t.Fatal("metadata was not deleted")
	}
}
