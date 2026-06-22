import os
import glob

base_dir = "/Users/sagarrawat/Desktop/afk-live/afklive-web/src/main/resources/static"
files = glob.glob(os.path.join(base_dir, "*.html"))

for path in files:
    with open(path, 'r') as file:
        content = file.read()
    
    # Replace the old logic
    old_logic1 = "if (localStorage.getItem('afk-theme') === 'dark' || (!('afk-theme' in localStorage) && window.matchMedia('(prefers-color-scheme: dark)').matches)) {"
    old_logic2 = "if (localStorage.theme === 'dark' || (!('theme' in localStorage) && window.matchMedia('(prefers-color-scheme: dark)').matches)) {"
    
    new_logic = "if (localStorage.getItem('afk-theme') !== 'light') {"
    
    content = content.replace("localStorage.theme", "localStorage.getItem('afk-theme')")
    content = content.replace("localStorage.theme =", "localStorage.setItem('afk-theme', ")
    content = content.replace("localStorage.setItem('afk-theme',  'light';", "localStorage.setItem('afk-theme', 'light');")
    content = content.replace("localStorage.setItem('afk-theme',  'dark';", "localStorage.setItem('afk-theme', 'dark');")
    
    content = content.replace(old_logic1, new_logic)
    content = content.replace(old_logic2, new_logic)
    
    with open(path, 'w') as file:
        file.write(content)

print("Done.")
