#!/usr/bin/env python3
"""
Nexus TV Gids — Production Asset Generator
Generates launcher icons, adaptive icons, and Leanback TV banners from
docs/logo-concepts/concept-1-nexus-beam.png.
"""

import os
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

SOURCE_IMAGE = "docs/logo-concepts/concept-1-nexus-beam.png"
FONT_BOLD = "android/library/src/main/res/font/open_sans_bold.ttf"
FONT_REGULAR = "android/library/src/main/res/font/open_sans_regular.ttf"

def load_source():
    im = Image.open(SOURCE_IMAGE).convert("RGBA")
    return im

def get_squircle_icon(im):
    bbox = im.getbbox()
    cropped = im.crop(bbox)
    w, h = cropped.size
    max_dim = max(w, h)
    canvas_size = int(max_dim * 1.05)
    square_icon = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    offset_x = (canvas_size - w) // 2
    offset_y = (canvas_size - h) // 2
    square_icon.paste(cropped, (offset_x, offset_y), cropped)
    return square_icon

def get_round_icon(squircle_icon, size):
    icon = squircle_icon.resize((size, size), Image.Resampling.LANCZOS)
    
    scale = 4
    mask = Image.new("L", (size * scale, size * scale), 0)
    draw = ImageDraw.Draw(mask)
    margin = int(size * scale * 0.03)
    draw.ellipse((margin, margin, size * scale - margin, size * scale - margin), fill=255)
    mask = mask.resize((size, size), Image.Resampling.LANCZOS)
    
    round_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    round_img.paste(icon, (0, 0), mask)
    return round_img

def get_foreground_emblem(im):
    w, h = im.size
    bg_r, bg_g, bg_b = 15, 18, 23
    
    emblem = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for y in range(200, 1000):
        for x in range(150, 1100):
            r, g, b, a = im.getpixel((x, y))
            diff = max(abs(r - bg_r), abs(g - bg_g), abs(b - bg_b))
            if diff > 8:
                if diff >= 40:
                    alpha = 255
                else:
                    alpha = int((diff - 8) / 32 * 255)
                emblem.putpixel((x, y), (r, g, b, alpha))
    
    bbox = emblem.getbbox()
    if bbox:
        return emblem.crop(bbox)
    return emblem

def generate_adaptive_foreground(emblem, size):
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    safe_size = int(size * 0.64)
    
    ew, eh = emblem.size
    scale = safe_size / max(ew, eh)
    new_w = int(ew * scale)
    new_h = int(eh * scale)
    
    scaled_emblem = emblem.resize((new_w, new_h), Image.Resampling.LANCZOS)
    pos_x = (size - new_w) // 2
    pos_y = (size - new_h) // 2
    
    canvas.paste(scaled_emblem, (pos_x, pos_y), scaled_emblem)
    return canvas

def generate_tv_banner(im, emblem, width, height):
    sw = width * 2
    sh = height * 2
    banner = Image.new("RGBA", (sw, sh), (13, 15, 18, 255))
    draw = ImageDraw.Draw(banner)
    
    # Background gradient: dark slate/navy
    for y in range(sh):
        factor = y / sh
        r = int(11 + factor * 5)
        g = int(14 + factor * 7)
        b = int(20 + factor * 12)
        draw.line([(0, y), (sw, y)], fill=(r, g, b, 255))
        
    # Bottom footer area
    footer_h = int(sh * 0.22)
    footer_top = sh - footer_h
    for y in range(footer_top, sh):
        draw.line([(0, y), (sw, y)], fill=(17, 20, 28, 255))
    draw.line([(0, footer_top), (sw, footer_top)], fill=(37, 99, 235, 140), width=max(1, int(sh * 0.007)))

    # Subtle cyan/blue ambient glow behind emblem
    glow_size = int(sh * 0.85)
    glow_x = int(sw * 0.23)
    glow_y = int(sh * 0.44)
    glow_overlay = Image.new("RGBA", (sw, sh), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow_overlay)
    glow_draw.ellipse(
        (glow_x - glow_size//2, glow_y - glow_size//2, glow_x + glow_size//2, glow_y + glow_size//2),
        fill=(37, 99, 235, 45)
    )
    glow_overlay = glow_overlay.filter(ImageFilter.GaussianBlur(radius=int(sh * 0.16)))
    banner = Image.alpha_composite(banner, glow_overlay)
    draw = ImageDraw.Draw(banner)

    # Place the Emblem on the left
    target_eh = int(sh * 0.54)
    ew, eh = emblem.size
    escale = target_eh / eh
    target_ew = int(ew * escale)
    scaled_emblem = emblem.resize((target_ew, target_eh), Image.Resampling.LANCZOS)
    
    emblem_x = int(sw * 0.10)
    emblem_y = (footer_top - target_eh) // 2 + int(sh * 0.02)
    banner.paste(scaled_emblem, (emblem_x, emblem_y), scaled_emblem)

    # Text branding on the right
    text_x = emblem_x + target_ew + int(sw * 0.075)
    
    font_size_title = int(sh * 0.23)
    font_size_sub = int(sh * 0.135)
    font_size_badge = int(sh * 0.072)
    
    try:
        font_title = ImageFont.truetype(FONT_BOLD, font_size_title)
        font_sub = ImageFont.truetype(FONT_BOLD, font_size_sub)
        font_badge = ImageFont.truetype(FONT_REGULAR, font_size_badge)
    except Exception as e:
        print(f"Warning: could not load TTF font ({e}), using default")
        font_title = ImageFont.load_default()
        font_sub = ImageFont.load_default()
        font_badge = ImageFont.load_default()

    # Draw Title: "NEXUS"
    title_y = int(sh * 0.18)
    draw.text((text_x, title_y), "NEXUS", fill=(248, 250, 252, 255), font=font_title)

    # Draw Subtitle: "TV GIDS"
    sub_y = title_y + int(font_size_title * 1.10)
    draw.text((text_x, sub_y), "TV GIDS", fill=(59, 130, 246, 255), font=font_sub)

    # Draw Leanback tag
    badge_text = "LEANBACK EPG • ANDROID TV"
    draw.text((int(sw * 0.10), footer_top + int(footer_h * 0.32)), badge_text, fill=(148, 163, 184, 200), font=font_badge)

    final_banner = banner.resize((width, height), Image.Resampling.LANCZOS)
    return final_banner

def main():
    print(f"Loading source image: {SOURCE_IMAGE}")
    src = load_source()
    squircle = get_squircle_icon(src)
    emblem = get_foreground_emblem(src)
    print(f"Emblem isolated: {emblem.size}")

    launcher_sizes = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192
    }
    adaptive_sizes = {
        "mdpi": 108,
        "hdpi": 162,
        "xhdpi": 216,
        "xxhdpi": 324,
        "xxxhdpi": 432
    }
    banner_sizes = {
        "mdpi": (160, 90),
        "hdpi": (240, 135),
        "xhdpi": (320, 180),
        "xxhdpi": (480, 270),
        "xxxhdpi": (640, 360)
    }

    base_res = "android/app/src/main/res"
    lib_res = "android/library/src/main/res"

    # 1. Launcher icons & round icons
    for density, size in launcher_sizes.items():
        icon = squircle.resize((size, size), Image.Resampling.LANCZOS)
        round_icon = get_round_icon(squircle, size)
        
        dir_path = os.path.join(base_res, f"mipmap-{density}")
        os.makedirs(dir_path, exist_ok=True)
        
        icon.save(os.path.join(dir_path, "ic_launcher.png"), "PNG")
        icon.save(os.path.join(dir_path, "ic_launcher_nexus_beam.png"), "PNG")
        round_icon.save(os.path.join(dir_path, "ic_launcher_round.png"), "PNG")
        print(f"Saved mipmap-{density} launcher icons ({size}x{size})")

    # 2. Adaptive foregrounds
    for density, size in adaptive_sizes.items():
        fg = generate_adaptive_foreground(emblem, size)
        dir_path = os.path.join(base_res, f"mipmap-{density}")
        fg.save(os.path.join(dir_path, "ic_launcher_foreground.png"), "PNG")
        print(f"Saved mipmap-{density} adaptive foreground ({size}x{size})")

    # 3. TV Banners
    for density, (w, h) in banner_sizes.items():
        banner = generate_tv_banner(src, emblem, w, h)
        dir_path = os.path.join(base_res, f"drawable-{density}")
        os.makedirs(dir_path, exist_ok=True)
        banner.save(os.path.join(dir_path, "tv_banner.png"), "PNG")
        banner.save(os.path.join(dir_path, "tv_banner_nexus_beam.png"), "PNG")
        print(f"Saved drawable-{density} banner ({w}x{h})")

    # Fallback main drawable tv_banner
    master_banner = generate_tv_banner(src, emblem, 640, 360)
    master_banner.save(os.path.join(base_res, "drawable", "tv_banner.png"), "PNG")
    master_banner.save(os.path.join(base_res, "drawable", "tv_banner_nexus_beam.png"), "PNG")
    print("Saved master drawable/tv_banner.png (640x360)")

    # In-app UI logo assets: tightly cropped emblem with transparent background
    ew, eh = emblem.size
    target_h = 128
    target_w = int(ew * (target_h / eh))
    in_app_logo = emblem.resize((target_w, target_h), Image.Resampling.LANCZOS)
    os.makedirs(os.path.join(lib_res, "drawable"), exist_ok=True)
    in_app_logo.save(os.path.join(lib_res, "drawable", "programguide_logo.png"), "PNG")
    in_app_logo.save(os.path.join(base_res, "drawable", "app_logo.png"), "PNG")
    print(f"Saved in-app UI logos ({target_w}x{target_h})")

    print("\nAll assets generated successfully!")

if __name__ == "__main__":
    main()
