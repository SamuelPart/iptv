import os
import sys
import glob
from PIL import Image

def crop_and_save_icons():
    # 1. Search for any uploaded image
    search_paths = [
        'image.png',
        '/home/user/uploads/*.png',
        '*.png'
    ]
    
    img_path = None
    for path_pattern in search_paths:
        matches = glob.glob(path_pattern)
        # Filter out existing app logos
        filtered = [m for m in matches if 'app_logo' not in m and 'logo_option' not in m]
        if filtered:
            img_path = filtered[0]
            break
            
    if not img_path:
        print("No se encontró ninguna imagen de iconos para recortar. Asegúrate de colocarla como 'image.png' en la raíz del proyecto.")
        return False
        
    print(f"Procesando imagen: {img_path}")
    try:
        img = Image.open(img_path)
        W, H = img.size
        
        # Slicing the image into 5 equal horizontal segments
        # The 5 icons are in order: AJUSTES, VIDEO, BUSCADOR, INICIO, TV
        names = ["ic_settings", "ic_movie", "ic_search", "ic_home", "ic_channels"]
        
        segment_width = W / 5.0
        icon_size = min(segment_width, H * 0.78) // 1 # perfect square icon size
        
        drawable_dir = "app/src/main/res/drawable"
        os.makedirs(drawable_dir, exist_ok=True)
        
        for i, name in enumerate(names):
            # Calculate horizontal center of each segment
            center_x = (i * segment_width) + (segment_width / 2.0)
            center_y = H * 0.40 # center of the 3D icons vertically
            
            # Define bounding box for the crop
            left = max(0, center_x - (icon_size / 2.0))
            top = max(0, center_y - (icon_size / 2.0))
            right = min(W, center_x + (icon_size / 2.0))
            bottom = min(H, center_y + (icon_size / 2.0))
            
            cropped = img.crop((left, top, right, bottom))
            # Resize to standard premium Android icon size (128x128 pixels)
            resized = cropped.resize((128, 128), Image.Resampling.LANCZOS)
            
            dest_path = os.path.join(drawable_dir, f"{name}.png")
            resized.save(dest_path, "PNG")
            print(f"Icono guardado con éxito: {dest_path}")
            
            # Delete duplicate .xml vector files to prevent "Duplicate resources" AGP build failures!
            xml_path = os.path.join(drawable_dir, f"{name}.xml")
            if os.path.exists(xml_path):
                os.remove(xml_path)
                print(f"Eliminado archivo XML duplicado: {xml_path}")
            
        print("¡Todos los 5 iconos fueron recortados y guardados de forma perfecta!")
        return True
    except Exception as e:
        print(f"Error al procesar la imagen: {e}")
        return False

if __name__ == '__main__':
    crop_and_save_icons()
