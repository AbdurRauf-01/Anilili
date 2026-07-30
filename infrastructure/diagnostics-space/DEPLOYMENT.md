# Free-tier deployment for `ilirkl`

The private bucket is `hf://buckets/ilirkl/anilili-diagnostics`. Do not place its
write token in the Android app.

On July 30, 2026, Hugging Face rejected both `zero-a10g` and `cpu-basic` creation for this account
with HTTP 402. The token has `repo.write`, and the account is older than 30 days, so this is an
account-level compute entitlement restriction rather than an authentication problem. Keep the
Android endpoint disabled until Hugging Face grants free compute eligibility or a community grant.

After Hugging Face confirms eligibility and `hf auth whoami` prints `ilirkl`, retry the
free-compatible runtime:

```powershell
hf repos create ilirkl/anilili-diagnostics `
  --type space `
  --space-sdk gradio `
  --public `
  --flavor zero-a10g `
  -v hf://buckets/ilirkl/anilili-diagnostics:/data
```

The receiver never imports `spaces` and never uses `@spaces.GPU`, so diagnostic requests remain in
the ordinary CPU process and should not consume ZeroGPU execution minutes.

Upload only the receiver directory to the Space:

```powershell
hf upload ilirkl/anilili-diagnostics infrastructure/diagnostics-space . --repo-type space
```

Wait for `https://ilirkl-anilili-diagnostics.hf.space/health` to return `{"status":"ok"}`. Then
build the Android app with the endpoint enabled:

```powershell
./gradlew assembleRelease `
  -PdiagnosticsUploadUrl=https://ilirkl-anilili-diagnostics.hf.space
```

Never enable the Android endpoint before the health check succeeds. Never select `cpu-upgrade`,
paid storage, persistent Space disk, or any paid GPU flavor.
