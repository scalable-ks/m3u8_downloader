# App Icon Processing

Source: `APP_ICON.png` (1024x1024).

Steps applied:
- Trimmed uniform outer margin: `convert APP_ICON.png -fuzz 6% -trim +repage APP_ICON_TRIM.png`
- Resized back to 1024x1024 for launcher masters: `convert APP_ICON_TRIM.png -resize 1024x1024 APP_ICON_1024.png`
- Generated Android launcher sizes from `APP_ICON_1024.png`:
  - mdpi 48x48
  - hdpi 72x72
  - xhdpi 96x96
  - xxhdpi 144x144
  - xxxhdpi 192x192

Output used:
- `RnAndroidHlsApp/android/app/src/main/res/mipmap-*/ic_launcher.png`
- `RnAndroidHlsApp/android/app/src/main/res/mipmap-*/ic_launcher_round.png`
