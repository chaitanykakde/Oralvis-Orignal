# Commands to Check NDK Installation on Mac

## Quick Check Commands

Run these commands in your Mac Terminal (Terminal.app or iTerm):

### 1. Check if NDK directory exists and list all versions:
```bash
# Check common SDK locations
ls -la ~/Library/Android/sdk/ndk/ 2>/dev/null || echo "NDK not found at ~/Library/Android/sdk/ndk/"
```

### 2. Find your Android SDK location:
```bash
# Check if ANDROID_HOME is set




ast login: Fri Dec 26 17:42:44 on console
chaitanyskakde@ChaitanySKakdes-MacBook-Pro ~ % ls -la ~/Library/Android/sdk/ndk/ 2>/dev/null || echo "NDK not found at ~/Library/Android/sdk/ndk/"
total 0
drwxr-xr-x   7 chaitanyskakde  staff  224 Dec  2 17:49 .
drwxr-xr-x  21 chaitanyskakde  staff  672 Aug 14 21:35 ..
drwxr-xr-x  22 chaitanyskakde  staff  704 Sep 20 23:47 25.1.8937393
drwxr-xr-x  22 chaitanyskakde  staff  704 Dec  2 17:49 26.1.10909125
drwxr-xr-x  22 chaitanyskakde  staff  704 Jul 21 13:00 26.3.11579264
drwxr-xr-x  22 chaitanyskakde  staff  704 Aug 18 00:58 27.0.12077973
drwxr-xr-x  22 chaitanyskakde  staff  704 Jul 21 12:44 29.0.13599879
chaitanyskakde@ChaitanySKakdes-MacBook-Pro ~ % 



# Check if ANDROID_SDK_ROOT is set
echo "ANDROID_SDK_ROOT: $ANDROID_SDK_ROOT"

# Try to find SDK in common locations
if [ -d "$HOME/Library/Android/sdk" ]; then
    echo "SDK found at: $HOME/Library/Android/sdk"
    ls -la "$HOME/Library/Android/sdk/ndk/" 2>/dev/null || echo "NDK directory not found"
fi
```

### 3. List all installed NDK versions (if found):
```bash
# If ANDROID_HOME is set
if [ ! -z "$ANDROID_HOME" ]; then
    echo "NDK versions in $ANDROID_HOME/ndk:"
    ls -la "$ANDROID_HOME/ndk/"
else
    # Try default location
    if [ -d "$HOME/Library/Android/sdk/ndk" ]; then
        echo "NDK versions in $HOME/Library/Android/sdk/ndk:"
        ls -la "$HOME/Library/Android/sdk/ndk/"
    else
        echo "NDK directory not found. Checking common locations..."
        find ~/Library/Android -name "ndk" -type d 2>/dev/null
    fi
fi
```

### 4. Check NDK_HOME environment variable:
```bash
echo "NDK_HOME: $NDK_HOME"
```

### 5. Comprehensive check (all-in-one):
```bash
echo "=== NDK Installation Check ==="
echo ""
echo "1. Environment Variables:"
echo "   ANDROID_HOME: ${ANDROID_HOME:-'Not set'}"
echo "   ANDROID_SDK_ROOT: ${ANDROID_SDK_ROOT:-'Not set'}"
echo "   NDK_HOME: ${NDK_HOME:-'Not set'}"
echo ""
echo "2. Checking common SDK locations:"
for sdk_path in "$HOME/Library/Android/sdk" "$ANDROID_HOME" "$ANDROID_SDK_ROOT"; do
    if [ ! -z "$sdk_path" ] && [ -d "$sdk_path" ]; then
        echo "   Found SDK at: $sdk_path"
        if [ -d "$sdk_path/ndk" ]; then
            echo "   NDK versions installed:"
            ls -1 "$sdk_path/ndk/" | sed 's/^/      - /'
        else
            echo "   No NDK directory found"
        fi
    fi
done
echo ""
echo "3. Searching for NDK installations:"
find ~/Library/Android -type d -name "ndk" 2>/dev/null | while read ndk_dir; do
    echo "   Found NDK directory: $ndk_dir"
    ls -1 "$ndk_dir" | sed 's/^/      - /'
done
```

## Copy-Paste Ready Commands

### Quick version (just list NDK versions):
```bash
ls -la ~/Library/Android/sdk/ndk/ 2>/dev/null || echo "NDK not found. Check ANDROID_HOME: $ANDROID_HOME"
```

### Detailed version (shows everything):
```bash
echo "ANDROID_HOME: $ANDROID_HOME"
echo "NDK_HOME: $NDK_HOME"
echo ""
if [ -d "$HOME/Library/Android/sdk/ndk" ]; then
    echo "Installed NDK versions:"
    ls -1 "$HOME/Library/Android/sdk/ndk/"
elif [ ! -z "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    echo "Installed NDK versions:"
    ls -1 "$ANDROID_HOME/ndk/"
else
    echo "NDK directory not found. Searching..."
    find ~/Library/Android -type d -name "ndk" 2>/dev/null
fi
```

## After Finding Your NDK Version

Once you know which NDK version is installed, you can:

1. **Set NDK_HOME in your shell profile** (add to `~/.zshrc` or `~/.bash_profile`):
   ```bash
   export NDK_HOME="$HOME/Library/Android/sdk/ndk/29.0.14206865"
   # Replace 29.0.14206865 with your actual version
   ```

2. **Or update your project's `local.properties`** (if you have one):
   ```
   ndk.dir=/Users/YourUsername/Library/Android/sdk/ndk/29.0.14206865
   ```

## Troubleshooting

If no NDK is found:
1. Open Android Studio
2. Go to: **Tools → SDK Manager → SDK Tools tab**
3. Check "Show Package Details"
4. Find "NDK (Side by side)" and install the version you need
5. The NDK will be installed at: `~/Library/Android/sdk/ndk/[version]`

