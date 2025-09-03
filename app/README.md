# Display Sizer Helpers (per-device, per-rotation)

Files:
- `com/limelight/utils/DisplaySizer.java`
- `com/limelight/utils/TextureViewSizer.java`
- `com/limelight/utils/SurfaceViewSizer.java`

## Usage (TextureView)
```java
TextureView tv = findViewById(R.id.video_tex);
TextureViewSizer sizer = new TextureViewSizer(this, tv, () -> {
    int[] sz = com.limelight.utils.DisplaySizer.getPresentationSizePx(this);
    if (renderer != null) renderer.setPresentationSizeHint(sz[0], sz[1]);
});
sizer.start(); // onResume
// sizer.stop(); // onPause
```

## Usage (SurfaceView)
```java
SurfaceView sv = findViewById(R.id.video_sv);
SurfaceViewSizer sizer = new SurfaceViewSizer(this, sv, () -> {
    int[] sz = com.limelight.utils.DisplaySizer.getPresentationSizePx(this);
    if (renderer != null) renderer.setPresentationSizeHint(sz[0], sz[1]);
});
sizer.start(); // onResume
// sizer.stop(); // onPause
```

> Tip: When constructing `GlUpscaleRenderer`, prefer the constructor with `Context` so it auto-reads the presentation size.
