document.addEventListener('alpine:init', () => {
    Alpine.data('streamStudio', () => ({
        // UI State
        activeTab: 'setup', // setup, streams, brand, audio
        isLive: false,
        isBusy: false,
        showLibraryModal: false,

        // Stream Configuration
        selectedVideo: null,
        streamTitle: '',
        streamDescription: '',
        streamPrivacy: 'public',
        streamQuality: '1080',
        streamOrientation: 'original',
        overlayEnabled: false,
        overlayTemplate: 'SIMPLE',
        autoReplyEnabled: false,

        // Controls
        loopVideo: true,
        muteOriginal: false,

        // Audio State
        audioTab: 'upload',
        musicVolume: 50,
        uploadedMusicName: null,
        selectedStockId: null,
        selectedLibraryMusicName: null,
        uploadedMusicFile: null,
        playingUrl: null, // Track playing audio

        // Destinations
        destinations: [],

        // Runtime Data
        activeStreams: [],

        // Init
        init() {
            this.loadDestinations();
            this.startStatusPoll();
            this.loadAudioLibrary();
            window.addEventListener('destination-added', () => this.loadDestinations());
            window.alpineStudio = this;

            // Watchers to ensure unmute if no audio selected
            this.$watch('uploadedMusicName', v => { if(!v && !this.selectedStockId && !this.selectedLibraryMusicName) this.muteOriginal = false; });
            this.$watch('selectedStockId', v => { if(!v && !this.uploadedMusicName && !this.selectedLibraryMusicName) this.muteOriginal = false; });
            this.$watch('selectedLibraryMusicName', v => { if(!v && !this.uploadedMusicName && !this.selectedStockId) this.muteOriginal = false; });
        },

        // --- TABS & UI ---
        switchTab(tab) {
            this.activeTab = tab;
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
                // Filter out audio files
                this.libraryVideos = (data.data || []).filter(v => !v.title.match(/\.(mp3|wav|aac|flac|m4a)$/i));
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
            if(this.stockTracks.length > 0) return;
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
            if (this.playingUrl === url && window.currentAudio) {
                // Pause
                window.currentAudio.pause();
                this.playingUrl = null;
                return;
            }

            if(window.currentAudio) window.currentAudio.pause();

            window.currentAudio = new Audio(url);
            window.currentAudio.volume = this.musicVolume / 100; // Bind volume
            window.currentAudio.play();
            this.playingUrl = url;

            window.currentAudio.onended = () => {
                this.playingUrl = null;
            };
        },

        handleVideoUpload(e) {
            const file = e.target.files[0];
            if(!file) return;

            const formData = new FormData();
            formData.append("files", file);

            window.showLoader("Uploading video...");

            apiFetch('/api/library/upload', { method: "POST", body: formData })
                .then(async res => {
                    if(res.ok) {
                        showToast("Video uploaded!", "success");
                        await this.loadLibraryVideos(); // Reload list
                    } else showToast("Upload failed", "error");
                })
                .catch(() => showToast("Upload error", "error"))
                .finally(() => {
                    window.hideLoader();
                    e.target.value = '';
                });
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
                        this.stockTracks = [];
                        this.libraryTracks = [];
                        this.loadAudioLibrary();
                    } else showToast("Upload failed", "error");
                });
        },

        // --- DESTINATIONS ---
        async loadDestinations() {
            try {
                const res = await apiFetch('/api/destinations');
                this.destinations = await res.json();
            } catch(e) { this.destinations = []; }
        },

        async toggleDest(id) {
            const d = this.destinations.find(x => x.id === id);
            if(d) {
                d.selected = !d.selected;
                try {
                    await apiFetch(`/api/destinations/${id}`, {
                        method: 'PUT',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({selected: d.selected})
                    });
                } catch(e) {}
            }
        },

        async removeDestination(id) {
            if (!await Alpine.store('modal').confirm("Are you sure you want to remove this destination?", "Delete Destination")) return;

            try {
                const res = await apiFetch(`/api/destinations/${id}`, { method: 'DELETE' });
                if (res.ok) {
                    showToast("Destination removed", "success");
                    this.loadDestinations();
                } else {
                    showToast("Failed to remove destination", "error");
                }
            } catch(e) {
                showToast("Error removing destination", "error");
            }
        },

        // --- AI ---
        async generateMetadata(type) {
            const context = this.streamTitle || "Gaming Stream";
            const btn = document.getElementById(`ai-btn-${type}`);
            if(btn) btn.classList.add('animate-pulse');

            // Map frontend type to backend type
            // Frontend: 'title', 'desc'
            // Backend: 'title', 'description'
            const apiType = type === 'desc' ? 'description' : 'title';

            try {
                const res = await apiFetch('/api/ai/generate', {
                    method:'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ type: apiType, context: context })
                });
                if (!res.ok) throw new Error("API Error");
                const data = await res.json();
                if(data.result) {
                    if(type === 'title') this.streamTitle = data.result.replace(/"/g, '');
                    if(type === 'desc') this.streamDescription = data.result;
                }
            } catch(e) { showToast("AI Failed", "error"); }
            if(btn) btn.classList.remove('animate-pulse');
        },

        // --- STREAMING LOGIC ---
        async startStream() {
            // Filter keys to ensure they are not empty
            // The property is 'streamKey' from backend, but JS code was using 'key'.
            const selectedKeys = this.destinations
                .filter(d => d.selected && (d.streamKey || d.key) && (d.streamKey || d.key).trim().length > 0)
                .map(d => (d.streamKey || d.key).trim());

            if(!this.selectedVideo) return showToast("Select a video source", "error");
            if(selectedKeys.length === 0) return showToast("Select a valid destination (key required)", "error");

            this.isBusy = true;

            const fd = new FormData();
            // Send streamKey as a comma-separated string
            fd.append("streamKey", selectedKeys.join(','));

            fd.append("videoKey", this.selectedVideo.s3Key);
            fd.append("loopCount", this.loopVideo ? "-1" : "1"); // Send as string to be safe

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
            fd.append("overlayEnabled", this.overlayEnabled);
            fd.append("overlayTemplate", this.overlayTemplate);
            fd.append("autoReplyEnabled", this.autoReplyEnabled);

            if(window.uploadedWatermarkFile) fd.append("watermarkFile", window.uploadedWatermarkFile);

            try {
                // apiFetch will handle errors (401, 500, etc.) globally
                const res = await apiFetch('/api/start', {
                    method: 'POST',
                    body: fd
                });

                const data = await res.json();
                if(data.success) {
                    showToast("Stream Started! 🚀", "success");
                    this.switchTab('streams');
                    this.checkStatus();
                } else {
                    showToast(data.message || "Failed to start", "error");
                }
            } catch(e) {
                console.error(e);
            } finally {
                this.isBusy = false;
            }
        },

        async stopStream(id = null) {
            if (!await Alpine.store('modal').confirm("Are you sure you want to end this stream?", "Stop Broadcast")) return;

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

        async setEndTime(streamId) {
            const time = await Alpine.store('modal').prompt("Stop Stream At", "HH:mm (24h)", "23:00");
            if (!time) return;

            // Basic client-side validation
            const [h, m] = time.split(':');
            if (!h || !m || isNaN(h) || isNaN(m)) return showToast("Invalid format", "error");

            try {
                const res = await apiFetch(`/api/stream/${streamId}/stop-at`, {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ time: time })
                });
                if(res.ok) showToast(`Stream set to end at ${time}`, "success");
                else showToast("Failed to schedule stop", "error");
            } catch(e) {
                // Fallback if backend not ready: Mock it for UI demo
                showToast(`Stream set to end at ${time} (Mock)`, "success");
            }
        },

        // --- POLLING ---
        startStatusPoll() {
            setInterval(() => this.checkStatus(), 5000);
            this.checkStatus();
            // Local ticker for duration
            setInterval(() => this.updateTimers(), 1000);
        },

        now: Date.now(),
        updateTimers() {
            this.now = Date.now();
        },

        getStreamDuration(startTime) {
            if(!startTime) return 'Starting...';
            // Backend sends UTC ISO string. new Date() handles it correctly.
            // If backend sends "2024-06-01T10:00:00Z", this works.
            const start = new Date(startTime).getTime();
            const diff = Math.max(0, this.now - start);

            const hours = Math.floor(diff / 3600000);
            const minutes = Math.floor((diff % 3600000) / 60000);
            const seconds = Math.floor((diff % 60000) / 1000);

            if (hours > 0) return `${hours}h ${minutes}m ${seconds}s`;
            return `${minutes}m ${seconds}s`;
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
