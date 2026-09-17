# DeadDrop deployment

DeadDrop uses one Go web service and one Render persistent disk. The disk stores
only encrypted package blobs and metadata needed for resume/expiration; there is
no database, Redis, worker, or server-side encryption key.

## Render

1. Push this working tree to the GitHub repository you want to deploy. No commit
   or push is performed by the development agent.
2. In Render Dashboard choose **New → Blueprint** and connect that repository.
3. Select the repository root `render.yaml` and create the Blueprint.
4. Keep the web service on a paid plan that supports a persistent disk. The
   Blueprint provisions the `encrypted-drops` disk at `/var/data`.
5. After the first healthy deploy, open the service and copy its HTTPS URL,
   for example `https://deaddrop-api-xxxx.onrender.com`.

The service creates random per-drop upload and download tokens. No Render API
key or application secret needs to be put in the repository. `DROP_TTL` defaults
to 24 hours and the aggregate storage guard defaults to 9 GiB in the Blueprint.
Change these environment values only if the attached disk is sized accordingly.

## Android configuration

The Android app has a **Render sunucusu** field. Paste the service HTTPS URL and
save it before sending a file. This value is stored as ordinary endpoint
configuration, never as a credential. For a preconfigured APK, replace the
placeholder `backend_api_base_url` in
`app/src/main/res/values/strings.xml` before building.

A generated share link has this shape:

```
https://service.onrender.com/v1/drops/<id>/content?token=<download-token>#key=<client-encryption-key>
```

The query token authorizes the backend. The `#key` fragment is consumed by the
DeadDrop app and is not sent in HTTP requests, so the backend never receives
the client encryption key.

## API lifecycle

- `POST /v1/drops` creates a drop and returns separate upload/download tokens.
- `PUT /v1/drops/<id>/content` accepts one bounded `Content-Range` chunk at a
  time and persists the current offset.
- `HEAD` returns the upload offset or download metadata.
- `GET` supports a single HTTP `Range` and returns only an encrypted blob.
- `DELETE` removes the drop after a verified receive or explicit cancellation.
- A service-local cleanup loop removes expired and incomplete drops. A separate
  Render cron is intentionally not used because it cannot share this disk.
