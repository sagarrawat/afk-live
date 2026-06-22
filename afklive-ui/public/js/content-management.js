document.addEventListener('alpine:init', () => {
    Alpine.data('contentStudio', () => ({
        // Library State
        libraryVideos: [],
        selectedVideos: new Set(),
        isLoadingLibrary: false,
        libPage: 1,
        libLimit: 20,
        libTotal: 0,
        libTotalPages: 0,

        init() {
            // Listen for global refreshes if needed
            window.addEventListener('refresh-content', () => {
                this.loadLibrary();
            });
        },

        // --- LIBRARY LOGIC ---
        async loadLibrary() {
            this.isLoadingLibrary = true;
            try {
                const res = await apiFetch(`/api/library?page=${this.libPage - 1}&size=${this.libLimit}`);
                const data = await res.json();

                const pageData = data.data; // Page object
                this.libraryVideos = (pageData.content || []).map(v => ({...v, progress: 0}));
                this.libTotal = pageData.totalElements;
                this.libTotalPages = pageData.totalPages;

                // Check if any need polling
                if (this.libraryVideos.some(v => v.optimizationStatus === 'IN_PROGRESS')) {
                    this.pollOptimizationProgress();
                }
            } catch(e) {
                console.error(e);
            } finally {
                this.isLoadingLibrary = false;
            }
        },

        optPollInterval: null,

        pollOptimizationProgress() {
            if (this.optPollInterval) clearInterval(this.optPollInterval);

            this.optPollInterval = setInterval(async () => {
                let activeCount = 0;

                for (let i = 0; i < this.libraryVideos.length; i++) {
                    const v = this.libraryVideos[i];
                    if (v.optimizationStatus === 'IN_PROGRESS') {
                        activeCount++;
                        try {
                            const res = await apiFetch(`/api/convert/status?fileName=${encodeURIComponent(v.title)}`);
                            const progress = await res.json(); // returns integer 0-100

                            // Update reactive array item
                            this.libraryVideos[i].progress = progress;

                            // If complete, maybe reload to get final status or just wait
                            if (progress >= 100) {
                                // Reload library after short delay to sync status
                                setTimeout(() => this.loadLibrary(), 2000);
                            }
                        } catch(e) {}
                    }
                }

                if (activeCount === 0) clearInterval(this.optPollInterval);
            }, 2000);
        },

        toggleSelection(video) {
            if (this.selectedVideos.has(video.id)) {
                this.selectedVideos.delete(video.id);
            } else {
                this.selectedVideos.add(video.id);
            }
            // Force reactivity for Set
            this.selectedVideos = new Set(this.selectedVideos);
        },

        isSelected(video) {
            return this.selectedVideos.has(video.id);
        },

        toggleSelectAll() {
            if (this.selectedVideos.size === this.libraryVideos.length) {
                this.selectedVideos.clear();
            } else {
                this.libraryVideos.forEach(v => this.selectedVideos.add(v.id));
            }
            this.selectedVideos = new Set(this.selectedVideos);
        },

        async deleteSelected() {
            if (this.selectedVideos.size === 0) return;
            if (!await Alpine.store('modal').confirm(`Delete ${this.selectedVideos.size} videos?`, "Delete Selected")) return;

            const ids = Array.from(this.selectedVideos);
            // Sequential delete or Promise.all - doing sequential to avoid rate limits/errors ideally
            for (const id of ids) {
                try {
                    await apiFetch(`/api/library/${id}`, { method: 'DELETE' });
                } catch(e) {}
            }
            this.selectedVideos.clear();
            this.loadLibrary();
            showToast("Videos deleted", "success");
        },

        async mergeSelected() {
            if (this.selectedVideos.size < 2) return;
            const ids = Array.from(this.selectedVideos);
            const titles = this.libraryVideos.filter(v => ids.includes(v.id)).map(v => v.title);

            if(!await Alpine.store('modal').confirm("Merge these videos into one?", "Merge Videos")) return;

            try {
                await apiFetch(`/api/library/merge`, {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ files: titles })
                });
                showToast("Merge started", "info");
                this.selectedVideos.clear();
            } catch(e) {
                showToast("Merge failed", "error");
            }
        },

        // --- UTILS ---
        get totalSizeMB() {
            const bytes = this.libraryVideos.reduce((acc, v) => acc + (v.fileSize || 0), 0);
            return (bytes / 1024 / 1024).toFixed(1);
        },

        previewVideo(video) {
            // Use existing global modal logic or reimplement
            // Reusing global function from app.js for now to save time, or we can make a local modal
            if(window.openPreviewModal) window.openPreviewModal(video.id);
        }
    }));
});
