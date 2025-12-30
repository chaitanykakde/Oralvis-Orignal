# NDK Setup for Mac

## Your Installed NDK Versions

Based on your Mac terminal output, you have the following NDK versions installed:
- `25.1.8937393`
- `26.1.10909125`
- **`26.3.11579264`** ← This matches your project's original configuration!
- `27.0.12077973`
- `29.0.13599879`

## Recommended Setup

Since your project was originally configured for **NDK 26.3.11579264** and you have it installed on Mac, we'll use that version.

### NDK Path on Your Mac:
```
/Users/chaitanyskakde/Library/Android/sdk/ndk/26.3.11579264
```

## Step 1: Set NDK_HOME Environment Variable

### Option A: Set in Shell Profile (Recommended)

1. **Open your shell profile file:**
   ```bash
   # For zsh (default on newer Macs)
   nano ~/.zshrc
   
   # OR for bash
   nano ~/.bash_profile
   ```

2. **Add these lines at the end:**
   ```bash
   # Android NDK
   export NDK_HOME="$HOME/Library/Android/sdk/ndk/26.3.11579264"
   export ANDROID_HOME="$HOME/Library/Android/sdk"
   export PATH="$PATH:$ANDROID_HOME/ndk/26.3.11579264"
   ```

3. **Save and exit:**
   - Press `Ctrl + X`
   - Press `Y` to confirm
   - Press `Enter` to save

4. **Reload your shell profile:**
   ```bash
   # For zsh
   source ~/.zshrc
   
   # OR for bash
   source ~/.bash_profile
   ```

5. **Verify it's set:**
   ```bash
   echo $NDK_HOME
   # Should output: /Users/chaitanyskakde/Library/Android/sdk/ndk/26.3.11579264
   ```

### Option B: Set for Current Session Only (Temporary)

```bash
export NDK_HOME="$HOME/Library/Android/sdk/ndk/26.3.11579264"
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

**Note:** This only works for the current terminal session. Use Option A for permanent setup.

## Step 2: Update Project Configuration

### Update `local.properties` (if you have one on Mac):

Create or update `local.properties` in your project root:
```properties
sdk.dir=/Users/chaitanyskakde/Library/Android/sdk
ndk.dir=/Users/chaitanyskakde/Library/Android/sdk/ndk/26.3.11579264
```

### Verify `build.gradle` uses correct version:

Your project's `build.gradle` should have:
```gradle
ndkVersion = '26.3.11579264'
```

## Step 3: Verify Setup

Run these commands to verify everything is set up correctly:

```bash
# Check environment variables
echo "NDK_HOME: $NDK_HOME"
echo "ANDROID_HOME: $ANDROID_HOME"

# Check if NDK exists
test -d "$NDK_HOME" && echo "✓ NDK directory exists" || echo "✗ NDK directory not found"

# Check if ndk-build exists
test -f "$NDK_HOME/ndk-build" && echo "✓ ndk-build found" || echo "✗ ndk-build not found"

# List NDK versions
ls -1 ~/Library/Android/sdk/ndk/
```

## Troubleshooting

### If NDK_HOME is not set after restarting terminal:

1. Check which shell you're using:
   ```bash
   echo $SHELL
   ```

2. Make sure you edited the correct profile file:
   - `/bin/zsh` → edit `~/.zshrc`
   - `/bin/bash` → edit `~/.bash_profile`

3. Verify the file was saved correctly:
   ```bash
   cat ~/.zshrc | grep NDK_HOME
   # OR
   cat ~/.bash_profile | grep NDK_HOME
   ```

### If you want to use a different NDK version:

You can use any of the installed versions. Just update:
1. `NDK_HOME` environment variable
2. `local.properties` (ndk.dir)
3. `build.gradle` (ndkVersion)

For example, to use version `29.0.13599879`:
```bash
export NDK_HOME="$HOME/Library/Android/sdk/ndk/29.0.13599879"
```

## Cross-Platform Note

Your Windows machine has NDK `29.0.14206865`, while your Mac has `26.3.11579264`. 

**Options:**
1. **Use different versions** - Update `local.properties` on each machine (recommended for now)
2. **Use same version** - Install `26.3.11579264` on Windows or `29.0.14206865` on Mac for consistency

The `local.properties` file is typically git-ignored, so each machine can have its own configuration.

