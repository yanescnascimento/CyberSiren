import os
import sys

try:
    from PIL import Image
except ImportError:
    print("This script requires the Pillow library. Please install it using: pip install Pillow")
    sys.exit(1)

# Configuration
# Path to the source image
SOURCE_IMAGE_PATH = 'a.png'

# Base directory for resources
BASE_RES_DIR = os.path.join('app', 'src', 'main', 'res')

# Configuration list: (folder_name, foreground_size, legacy_size)
# foreground_size corresponds to ic_launcher_adaptive_fore.png (adaptive icon foreground)
# legacy_size corresponds to ic_launcher.png (legacy icon)
CONFIGS = [
    ('mipmap-mdpi', 108, 48),       # Medium density
    ('mipmap-hdpi', 162, 72),       # High density
    ('mipmap-xhdpi', 216, 96),      # Extra-high density
    ('mipmap-xxhdpi', 324, 144),    # Extra-extra-high density
    ('mipmap-xxxhdpi', 432, 192),   # Extra-extra-extra-high density
]

def generate_icons():
    # Verify source image exists
    if not os.path.exists(SOURCE_IMAGE_PATH):
        print(f"Error: Source image '{SOURCE_IMAGE_PATH}' not found in the current directory.")
        return

    try:
        img = Image.open(SOURCE_IMAGE_PATH)
        print(f"Loaded source image: {SOURCE_IMAGE_PATH} ({img.size[0]}x{img.size[1]})")
    except Exception as e:
        print(f"Error opening source image: {e}")
        return

    # Use LANCZOS for high-quality downsampling if available, otherwise fallback
    resample_filter = getattr(Image.Resampling, 'LANCZOS', getattr(Image, 'LANCZOS', Image.BICUBIC))

    for folder_name, fore_size, legacy_size in CONFIGS:
        folder_path = os.path.join(BASE_RES_DIR, folder_name)
        
        # Create directory if it doesn't exist (though user said overwrite folders/files)
        if not os.path.exists(folder_path):
            print(f"Creating directory: {folder_path}")
            os.makedirs(folder_path, exist_ok=True)
        
        # Define output paths
        fore_path = os.path.join(folder_path, 'ic_launcher_adaptive_fore.png')
        legacy_path = os.path.join(folder_path, 'ic_launcher.png')

        # 1. Generate Adaptive Foreground Icon
        print(f"[{folder_name}] Generating foreground ({fore_size}x{fore_size})...")
        try:
            img_fore = img.resize((fore_size, fore_size), resample_filter)
            img_fore.save(fore_path)
        except Exception as e:
            print(f"Failed to save {fore_path}: {e}")

        # 2. Generate Legacy Icon
        print(f"[{folder_name}] Generating legacy icon ({legacy_size}x{legacy_size})...")
        try:
            img_legacy = img.resize((legacy_size, legacy_size), resample_filter)
            img_legacy.save(legacy_path)
        except Exception as e:
            print(f"Failed to save {legacy_path}: {e}")

        # 3. Generate Round Icon
        round_path = os.path.join(folder_path, 'ic_launcher_round.png')
        print(f"[{folder_name}] Generating round icon ({legacy_size}x{legacy_size})...")
        try:
           img_round = img.resize((legacy_size, legacy_size), resample_filter)
           img_round.save(round_path)
        except Exception as e:
           print(f"Failed to save {round_path}: {e}")

    print("\nIcon generation complete!")

if __name__ == "__main__":
    generate_icons()
