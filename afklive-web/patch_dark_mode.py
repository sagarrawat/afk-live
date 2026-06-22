import os

files = ["terms.html", "privacy.html", "refund.html", "disclaimer.html"]
base_dir = "/Users/sagarrawat/Desktop/afk-live/afklive-web/src/main/resources/static"

dark_style = """
        .glass-nav { background: rgba(255, 255, 255, 0.8); backdrop-filter: blur(12px); border-bottom: 1px solid rgba(0,0,0,0.05); }
        .dark .glass-nav { background: rgba(9, 9, 11, 0.8) !important; border-bottom: 1px solid rgba(255,255,255,0.05) !important; }
        .dark .prose h1, .dark .prose h2, .dark .prose h3 { color: #f9fafb !important; }
        .dark .prose p, .dark .prose li { color: #9ca3af !important; }
        .dark .prose a { color: #60a5fa !important; }
        .dark .bg-gray-100 { background-color: #27272a !important; color: #f9fafb !important; }
"""

toggle_script = """
    <script>
        function toggleTheme() {
            if (document.documentElement.classList.contains('dark')) {
                document.documentElement.classList.remove('dark');
                localStorage.theme = 'light';
            } else {
                document.documentElement.classList.add('dark');
                localStorage.theme = 'dark';
            }
        }
        if (localStorage.theme === 'dark' || (!('theme' in localStorage) && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
            document.documentElement.classList.add('dark');
        } else {
            document.documentElement.classList.remove('dark');
        }
    </script>
</head>
"""

nav_button = """
            <div class="flex items-center gap-4">
                <button onclick="toggleTheme()" class="text-gray-500 hover:text-blue-600 transition mr-2" aria-label="Toggle Dark Mode">
                    <svg class="w-5 h-5 dark:hidden" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z" /></svg>
                    <svg class="w-5 h-5 hidden dark:block" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z" /></svg>
                </button>
"""

for f in files:
    path = os.path.join(base_dir, f)
    with open(path, 'r') as file:
        content = file.read()
    
    if 'toggleTheme' in content: continue # Already patched
    
    content = content.replace('<meta name="color-scheme" content="light only">', '<meta name="color-scheme" content="light dark">')
    content = content.replace('theme: {', "darkMode: 'class',\n            theme: {")
    content = content.replace('.glass-nav { background: rgba(255, 255, 255, 0.8); backdrop-filter: blur(12px); border-bottom: 1px solid rgba(0,0,0,0.05); }', dark_style)
    content = content.replace('<body class="bg-white text-gray-900', '<body class="bg-white dark:bg-[#09090b] text-gray-900 dark:text-gray-100')
    content = content.replace('</head>', toggle_script)
    content = content.replace('<div class="flex items-center gap-4">', nav_button)
    
    with open(path, 'w') as file:
        file.write(content)

print("Done patching.")
