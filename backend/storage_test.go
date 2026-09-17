package main

import (
	"bytes"
	"io"
	"net/http"
	"os"
	"testing"
)

func TestAggregateStorageLimitRejectsUpload(t *testing.T) {
	server, httpServer := testAPI(t)
	server.maxStorageBytes = 1
	drop := createTestDrop(t, httpServer.URL, 8)
	response := uploadTestChunk(t, httpServer.URL+drop.DownloadPath, drop.UploadToken,
		0, 8, []byte("payload!"))
	response.Body.Close()
	if response.StatusCode != http.StatusInsufficientStorage {
		t.Fatalf("storage limit status: %d", response.StatusCode)
	}
}

func TestCompletedBlobRecoversAfterMetadataSaveFailure(t *testing.T) {
	server, httpServer := testAPI(t)
	payload := []byte("encrypted")
	drop := createTestDrop(t, httpServer.URL, int64(len(payload)))
	if err := os.WriteFile(server.store.blobPath(drop.ID), payload, 0600); err != nil {
		t.Fatal(err)
	}
	metadata, err := server.store.load(drop.ID)
	if err != nil {
		t.Fatal(err)
	}
	metadata.State = "uploading"
	metadata.Offset = 0
	if err := server.store.save(metadata); err != nil {
		t.Fatal(err)
	}

	request, err := http.NewRequest(http.MethodHead, httpServer.URL+drop.DownloadPath, nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+drop.UploadToken)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusOK || response.Header.Get("X-Upload-Offset") != "9" {
		t.Fatalf("recovery response: status=%d offset=%q", response.StatusCode, response.Header.Get("X-Upload-Offset"))
	}

	request, err = http.NewRequest(http.MethodGet, httpServer.URL+drop.DownloadPath, nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+drop.DownloadToken)
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	result, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK || !bytes.Equal(result, payload) {
		t.Fatalf("recovered download: status=%d body=%q", response.StatusCode, result)
	}
}
