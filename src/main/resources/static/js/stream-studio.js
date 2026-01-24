document.addEventListener('alpine:init', () => {
    Alpine.data('streamStudio', () => ({
        // UI State
        activeTab: 'streams', // streams, chat, brand, audio, settings
        isLive: false,
        isBusy: false,
        showLibraryModal: false,

        // Stream Configuration
        selectedVideo: null, // { id, title, thumbnailS3Key, optimizationStatus, s3Key, ... }
        streamTitle: '',
        streamDescription: '',
        streamPrivacy: 'public',
        streamQuality: '1080',
        streamOrientation: 'original',

        // Controls
        loopVideo: true,
        muteOriginal: true,

        // Audio State
        audioTab: 'upload', // upload, lib, mylib
        musicVolume: 50,
        uploadedMusicName: null,
        selectedStockId: null,
        selectedLibraryMusicName: null,
        uploadedMusicFile: null, // For form data

        // Destinations
        destinations: [],

        // Runtime Data
        activeStreams: [],
        chatThreads: [],

        // Init
        init() {
            this.loadDestinations();
            this.startStatusPoll();
            this.loadAudioLibrary(); // Pre-load or load on tab switch

            // Listen for external events if needed
            window.addEventListener('destination-added', () => this.loadDestinations());

            // Expose for debugging
            window.alpineStudio = this;
        },

        // --- TABS & UI ---
        switchTab(tab) {
            this.activeTab = tab;
            if(tab === 'chat') this.loadChat();
            if(tab === 'audio') this.loadAudioLibrary();
        },

        formatDuration(startTime) {
            if(!startTime) return 'Live';
            const start = new Date(startTime);
            const now = new Date();
            const diffMs = now - start;
            const hrs = Math.floor(diffMs / 3600000);
            const mins = Math.floor((diffMs % 3600000) / 60000);
            return `${hrs}h ${mins}m`;
        },

        // --- SOURCES ---
        // Open the global library modal (we might eventually make this local)
        openSourceSelector() {
            // For now, we can reuse the existing app.js modal or build a better one.
            // Let's try to build a better one inside the new UI, but for Step 3 (Redesign).
            // For now, let's toggle a flag that the UI will use to show a modal.
            this.showLibraryModal = true;
            this.loadLibraryVideos();
        },

        libraryVideos: [],
        isLoadingLibrary: false,

        async loadLibraryVideos() {
            this.isLoadingLibrary = true;
            try {
                const res = await apiFetch('/api/library');
                const data = await res.json();
                this.libraryVideos = data.data || [];
            } catch(e) {
                console.error(e);
                showToast("Failed to load library", "error");
            } finally {
                this.isLoadingLibrary = false;
            }
        },

        selectVideo(video) {
            if(video.optimizationStatus !== 'COMPLETED') {
                showToast("Video needs optimization first", "warning");
                // Trigger optimization modal from app.js if needed or handle here
                if(window.openOptimizeModal) window.openOptimizeModal(video);
                return;
            }
            this.selectedVideo = video;
            if(!this.streamTitle) this.streamTitle = video.title.replace(/\.[^/.]+$/, "");

            // Update Preview
            const player = document.getElementById('previewPlayer'); // We will ensure this ID exists in new UI
            if(player) {
                player.src = `/api/library/stream/${video.id}`;
                player.load();
            }
            this.showLibraryModal = false;
        },

        // --- AUDIO ---
        stockTracks: [],
        libraryTracks: [],

        async loadAudioLibrary() {
            if(this.stockTracks.length > 0) return; // Already loaded

            try {
                const [resStock, resLib] = await Promise.all([
                    apiFetch('/api/audio/trending'),
                    apiFetch('/api/audio/my-library')
                ]);
                this.stockTracks = await resStock.json();
                this.libraryTracks = await resLib.json();
            } catch(e) { console.error(e); }
        },

        previewAudio(url) {
            if(window.currentAudio) window.currentAudio.pause();
            window.currentAudio = new Audio(url);
            window.currentAudio.play();
        },

        handleVideoUpload(e) {
            const file = e.target.files[0];
            if(!file) return;

            const formData = new FormData();
            formData.append("files", file);

            showToast("Uploading video...", "info");
            this.isLoadingLibrary = true;

            apiFetch('/api/library/upload', { method: "POST", body: formData })
                .then(async res => {
                    if(res.ok) {
                        showToast("Video uploaded!", "success");
                        await this.loadLibraryVideos(); // Reload list
                        // Optionally select it automatically if we can find it
                    } else showToast("Upload failed", "error");
                })
                .catch(() => showToast("Upload error", "error"))
                .finally(() => this.isLoadingLibrary = false);
        },

        handleMusicUpload(e) {
            const file = e.target.files[0];
            if(!file) return;

            // We upload immediately to get it ready? Or just hold file for stream start?
            // app.js did upload immediately. Let's replicate.
            const formData = new FormData();
            formData.append("files", file);

            showToast("Uploading music...", "info");
            apiFetch('/api/library/upload', { method: "POST", body: formData })
                .then(res => {
                    if(res.ok) {
                        showToast("Music uploaded!", "success");
                        this.uploadedMusicName = file.name;
                    } else showToast("Upload failed", "error");
                });
        },

        // --- DESTINATIONS ---
        loadDestinations() {
            const saved = localStorage.getItem('afk_destinations');
            if(saved) this.destinations = JSON.parse(saved);
        },

        toggleDest(id) {
            const d = this.destinations.find(x => x.id === id);
            if(d) {
                d.selected = !d.selected;
                localStorage.setItem('afk_destinations', JSON.stringify(this.destinations));
            }
        },

        // --- STREAMING LOGIC ---
        async startStream() {
            const selectedKeys = this.destinations.filter(d => d.selected).map(d => d.key);

            if(!this.selectedVideo) return showToast("Select a video source", "error");
            if(selectedKeys.length === 0) return showToast("Select a destination", "error");

            this.isBusy = true;

            const fd = new FormData();
            selectedKeys.forEach(k => fd.append("streamKey", k));
            fd.append("videoKey", this.selectedVideo.s3Key);
            fd.append("loopCount", this.loopVideo ? -1 : 1);

            // Audio Logic
            const vol = (this.musicVolume / 100).toFixed(1);
            if (this.audioTab === 'upload' && this.uploadedMusicName) {
                fd.append("musicName", this.uploadedMusicName);
                fd.append("musicVolume", vol);
            } else if (this.audioTab === 'mylib' && this.selectedLibraryMusicName) {
                fd.append("musicName", this.selectedLibraryMusicName);
                fd.append("musicVolume", vol);
            } else if (this.audioTab === 'lib' && this.selectedStockId) {
                fd.append("musicName", "stock:" + this.selectedStockId);
                fd.append("musicVolume", vol);
            }

            fd.append("muteVideoAudio", this.muteOriginal);

            if(this.streamTitle) fd.append("title", this.streamTitle);
            if(this.streamDescription) fd.append("description", this.streamDescription);
            fd.append("privacy", this.streamPrivacy);
            fd.append("streamMode", this.streamOrientation);
            fd.append("streamQuality", this.streamQuality);

            // Watermark (global var from app.js for now, or we can reimplement)
            if(window.uploadedWatermarkFile) fd.append("watermarkFile", window.uploadedWatermarkFile);

            try {
                const res = await apiFetch('/api/start', { method:'POST', body:fd });
                const data = await res.json();
                if(data.success) {
                    showToast("Stream Started! 🚀", "success");
                    this.isLive = true;
                    this.checkStatus();
                } else {
                    showToast(data.message || "Failed to start", "error");
                }
            } catch(e) {
                showToast("Error starting stream", "error");
            } finally {
                this.isBusy = false;
            }
        },

        async stopStream(id = null) {
            if(!confirm("Stop broadcast?")) return;
            this.isBusy = true;
            try {
                let url = '/api/stop';
                if(id) url += `?streamId=${id}`;

                await apiFetch(url, { method:'POST' });
                showToast("Stream Stopped", "info");
                this.checkStatus();
            } catch(e) {
                showToast("Failed to stop", "error");
            } finally {
                this.isBusy = false;
            }
        },

        // --- CHAT ---
        async loadChat() {
             try {
                const res = await apiFetch('/api/comments');
                const data = await res.json();
                this.chatThreads = data.items || [];
            } catch(e) {}
        },

        // --- POLLING ---
        startStatusPoll() {
            setInterval(() => this.checkStatus(), 5000);
            this.checkStatus();
        },

        async checkStatus() {
            try {
                const res = await apiFetch('/api/status');
                const data = await res.json();
                if(data.success) {
                    this.activeStreams = data.data.activeStreams || [];
                    this.isLive = this.activeStreams.some(s => s.live);
                }
            } catch(e) {}
        }
    }));
});

// Global Watermark Helpers
window.handleWatermarkUpload = function(input) {
    if (input.files && input.files[0]) {
        const reader = new FileReader();
        reader.onload = function(e) {
            const img = document.getElementById('watermarkImg');
            const preview = document.getElementById('watermarkPreview');
            if(img && preview) {
                img.src = e.target.result;
                preview.classList.remove('hidden');
            }
        };
        reader.readAsDataURL(input.files[0]);
        window.uploadedWatermarkFile = input.files[0];
    }
};

window.clearWatermark = function() {
    const input = document.getElementById('streamWatermarkFile');
    if(input) input.value = '';
    window.uploadedWatermarkFile = null;
    document.getElementById('watermarkPreview').classList.add('hidden');
};
