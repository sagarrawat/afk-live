const API_URL = "/api";
let currentUser = null;

// On Load
document.addEventListener("DOMContentLoaded", async () => {
    await fetchUserInfo();
    loadGlobalSettings();
    checkYoutubeStatus();
    loadUserChannels();
    loadScheduledQueue();

    // Event Listeners
    setupEventListeners();

    // Global Click for Dropdown
    window.addEventListener('click', (e) => {
        if (!e.target.closest('.sidebar-header')) {
            const menu = document.getElementById('channelDropdownMenu');
            if (menu && menu.classList.contains('show')) menu.classList.remove('show');
        }
    });
});

function setupEventListeners() {
    // Drop Zone
    const dropZone = document.getElementById("dropZone");
    if(dropZone) {
        dropZone.addEventListener("click", () => document.getElementById("scheduleFile").click());
        dropZone.addEventListener("dragover", (e) => { e.preventDefault(); dropZone.style.background = "#e3f2fd"; });
        dropZone.addEventListener("dragleave", (e) => { e.preventDefault(); dropZone.style.background = ""; });
        dropZone.addEventListener("drop", (e) => {
            e.preventDefault();
            dropZone.style.background = "";
            const files = e.dataTransfer.files;
            if(files.length) handleFileSelect(files[0]);
        });
    }

    const sFile = document.getElementById("scheduleFile");
    if(sFile) sFile.addEventListener("change", (e) => handleFileSelect(e.target.files[0]));

    // Bulk Upload
    const bulkInput = document.getElementById("bulkUploadInput");
    if(bulkInput) bulkInput.addEventListener("change", handleBulkUpload);

    // Stream Upload
    const streamUpload = document.getElementById("streamUploadInput");
    if(streamUpload) streamUpload.addEventListener("change", handleStreamVideoUpload);

    // Stream Music Upload
    const streamMusicUpload = document.getElementById("streamAudioFile");
    if(streamMusicUpload) streamMusicUpload.addEventListener("change", handleStreamMusicUpload);

    // Thumbnail Upload
    const thumbInput = document.getElementById("scheduleThumbnail");
    if(thumbInput) thumbInput.addEventListener("change", handleThumbnailSelect);

    // Live Preview
    document.getElementById('scheduleTitle')?.addEventListener('input', e => {
        document.getElementById('previewTitleMock').innerText = e.target.value || "Video Title";
    });
    document.getElementById('scheduleDescription')?.addEventListener('input', e => {
        document.getElementById('previewDescMock').innerText = e.target.value || "Description will appear here...";
    });
}

/* --- NAVIGATION & VIEW SWITCHING --- */
function switchView(viewName) {
    // Hide all views
    document.querySelectorAll('.view-section').forEach(el => el.classList.add('hidden'));

    // Show target view
    const target = document.getElementById(`view-${viewName}`);
    if(target) target.classList.remove('hidden');

    // Update Menu Active State
    document.querySelectorAll('.menu-item').forEach(el => el.classList.remove('active'));
    document.querySelector(`[data-target="view-${viewName}"]`)?.classList.add('active');

    // Close Mobile Menu if open
    const sb = document.getElementById('subSidebar');
    if (sb && sb.classList.contains('open')) {
        toggleMobileMenu();
    }

    // Update Header Title (Mobile/Sidebar)
    const titles = {
        'publish': 'Publishing',
        'stream': 'Live Studio',
        'calendar': 'Calendar',
        'analytics': 'Analytics',
        'library': 'Media Library',
        'community': 'Engagement',
        'settings': 'Settings'
    };
    const title = titles[viewName] || 'AFK Live';
    const sidebarTitle = document.getElementById('sidebarTitle');
    if(sidebarTitle) sidebarTitle.innerText = title;

    // View specific init
    if (viewName === 'calendar') setTimeout(initCalendar, 100);
    if (viewName === 'analytics') setTimeout(initAnalytics, 100);
    if (viewName === 'library') loadLibraryVideos();
    if (viewName === 'community') loadComments();
    if (viewName === 'stream') {
        // Init Stream Studio
        checkStreamStatus();
        switchStudioTab('streams');
        loadDestinations();
        loadStreamAudioLibrary();
    }

    // Close mobile menu if open
    document.querySelector('.sub-sidebar')?.classList.remove('open');
}

function toggleMobileMenu() {
    const sb = document.getElementById('subSidebar');
    const overlay = document.getElementById('sidebarOverlay');

    if (sb.classList.contains('open')) {
        sb.classList.remove('open');
        if(overlay) overlay.classList.remove('active');
    } else {
        sb.classList.add('open');
        if(overlay) overlay.classList.add('active');
    }
}

/* --- STREAM STUDIO (NEW) --- */
function switchStudioTab(tab) {
    document.querySelectorAll('.studio-tab').forEach(b => b.classList.remove('active'));
    document.querySelector(`.studio-tab[onclick*="'${tab}'"]`)?.classList.add('active');

    document.querySelectorAll('.studio-panel').forEach(p => p.classList.remove('active'));
    document.getElementById(`studio-tab-${tab}`)?.classList.add('active');
}

function switchStreamTab(tab) {
    if (tab === 'schedule') {
        document.getElementById('streamActionSchedule').classList.toggle('hidden');
    }
}

function switchStreamAudioTab(tab) {
    // Buttons
    document.querySelectorAll('.studio-audio-control button').forEach(b => b.classList.remove('btn-primary', 'btn-outline'));
    document.querySelectorAll('.studio-audio-control button').forEach(b => {
        if(b.getAttribute('onclick').includes(`'${tab}'`)) {
            b.classList.add('btn-primary');
        } else {
            b.classList.add('btn-outline');
        }
    });

    // Sections
    document.getElementById('streamAudioUploadSection').classList.add('hidden');
    document.getElementById('streamAudioLibSection').classList.add('hidden');
    document.getElementById('streamAudioMyLibSection').classList.add('hidden');

    if (tab === 'upload') document.getElementById('streamAudioUploadSection').classList.remove('hidden');
    if (tab === 'lib') {
        document.getElementById('streamAudioLibSection').classList.remove('hidden');
        if(document.getElementById('streamAudioTrackList').innerHTML === 'Loading...') loadStreamAudioLibrary();
    }
    if (tab === 'mylib') {
        document.getElementById('streamAudioMyLibSection').classList.remove('hidden');
        if(document.getElementById('streamAudioMyTrackList').innerHTML === 'Loading...') loadStreamAudioLibrary();
    }
}

let streamAudioLibraryLoaded = false;
async function loadStreamAudioLibrary() {
    if(streamAudioLibraryLoaded) return;

    // Load Stock
    try {
        const res = await apiFetch(`${API_URL}/audio/trending`);
        const data = await res.json();
        const list = document.getElementById('streamAudioTrackList');
        list.innerHTML = '';
        data.forEach(t => {
            const div = document.createElement('div');
            div.className = 'queue-item';
            div.style.cursor = 'pointer';
            div.innerHTML = `<i class="fa-solid fa-music"></i> <div style="flex:1; margin-left:10px;">${t.title}</div>`;
            div.onclick = () => {
                document.querySelectorAll('#streamAudioTrackList .queue-item').forEach(e=>e.classList.remove('active-track'));
                div.classList.add('active-track');
                document.getElementById('selectedStreamStockId').value = t.id; // Assuming ID is title or needed param
            };
            list.appendChild(div);
        });
    } catch(e) { document.getElementById('streamAudioTrackList').innerHTML = 'Failed to load'; }

    // Load My Library
    try {
        const res = await apiFetch(`${API_URL}/audio/my-library`);
        const data = await res.json();
        const list = document.getElementById('streamAudioMyTrackList');
        list.innerHTML = '';
        if(data.length === 0) list.innerHTML = '<div style="padding:10px; color:#666; font-size:0.8rem;">No audio files found in library.</div>';

        data.forEach(t => {
            const div = document.createElement('div');
            div.className = 'queue-item';
            div.style.cursor = 'pointer';
            div.innerHTML = `<i class="fa-solid fa-file-audio"></i> <div style="flex:1; margin-left:10px;">${t.title}</div>`;
            div.onclick = () => {
                document.querySelectorAll('#streamAudioMyTrackList .queue-item').forEach(e=>e.classList.remove('active-track'));
                div.classList.add('active-track');
                document.getElementById('selectedMyLibMusicName').value = t.title; // submitJob uses musicName
            };
            list.appendChild(div);
        });
    } catch(e) { document.getElementById('streamAudioMyTrackList').innerHTML = 'Failed to load'; }

    streamAudioLibraryLoaded = true;
}

async function handleStreamMusicUpload(e) {
    const file = e.target.files[0];
    if (!file) return;

    const btn = e.target.previousElementSibling || e.target; // Fallback
    // Visual feedback not easy on file input, using Toast
    showToast("Uploading music...", "info");

    const formData = new FormData();
    formData.append("files", file);

    try {
        const res = await apiFetch(`${API_URL}/library/upload`, { method: "POST", body: formData });
        if (res.ok) {
            showToast("Music uploaded!", "success");
            document.getElementById('uploadedStreamMusicName').value = file.name;
        } else {
            showToast("Upload failed.", "error");
        }
    } catch (err) {
        showToast("Upload error.", "error");
    }
}

async function generateStreamMetadata() {
    const topic = document.getElementById('aiStreamTopic').value || document.getElementById('streamMetaTitle').value || "General Stream";
    const btn = document.querySelector('button[onclick="generateStreamMetadata()"]');
    const originalText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Generating...';

    try {
        const res = await apiFetch(`${API_URL}/ai/generate`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ type: 'stream_metadata', context: topic })
        });
        const data = await res.json();
        if(data.result) {
            // Assume result is JSON string or formatted text.
            // If it's simple text, put in description. If JSON, parse it.
            // For now, let's assume the AI returns a JSON-like string or just description.
            // But usually 'stream_metadata' implies structured data.
            // Let's try to parse if it looks like JSON
            try {
                const parsed = JSON.parse(data.result);
                if(parsed.title) document.getElementById('streamMetaTitle').value = parsed.title;
                if(parsed.description) document.getElementById('streamMetaDesc').value = parsed.description;
                if(parsed.tags) document.getElementById('aiStreamTopic').value = parsed.tags; // Reuse hidden field for tags
            } catch(jsonErr) {
                // Fallback: result is description
                document.getElementById('streamMetaDesc').value = data.result;
            }
            showToast("Metadata generated!", "success");
        }
    } catch(e) { showToast("AI Generation Failed", "error"); }
    finally {
        btn.disabled = false;
        btn.innerHTML = originalText;
    }
}

// Reuse toggleLoop
function toggleLoop(chk) {
    const input = document.getElementById('streamLoopCount');
    if(chk.checked) {
        input.classList.add('hidden');
    } else {
        input.classList.remove('hidden');
    }
}

async function handleStreamVideoUpload(e) {
    const file = e.target.files[0];
    if (!file) return;

    const btn = document.querySelector('button[onclick*="streamUploadInput"]');
    const originalHTML = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = `<i class="fa-solid fa-spinner fa-spin"></i> Uploading...`;

    const formData = new FormData();
    formData.append("files", file);

    try {
        const res = await apiFetch(`${API_URL}/library/upload`, { method: "POST", body: formData });
        if (res.ok) {
            showToast("Video uploaded successfully!", "success");
            openLibraryModalForStream();
            fetchUserInfo();
        } else {
            showToast("Upload failed.", "error");
        }
    } catch (err) {
        showToast("Upload error.", "error");
    } finally {
        btn.disabled = false;
        btn.innerHTML = originalHTML;
        e.target.value = '';
    }
}

async function openLibraryModalForStream() {
    document.getElementById('streamLibraryModal').classList.remove('hidden');
    const list = document.getElementById('streamLibraryList');
    list.innerHTML = "Loading...";

    try {
        const res = await apiFetch(`${API_URL}/library`);
        const data = await res.json();
        list.innerHTML = '';

        if(!data.data || data.data.length === 0) {
            list.innerHTML = "No videos in library.";
            return;
        }

        data.data.forEach(v => {
             const div = document.createElement('div');
             div.className = 'queue-item';
             div.style.cursor = 'pointer';

             let status = '';
             if (v.optimizationStatus === 'COMPLETED') status = '<span class="badge" style="background:#00875a; color:white; font-size:0.6rem; margin-left:5px;">OPTIMIZED</span>';

             div.innerHTML = `<i class="fa-solid fa-film"></i> ${v.title} ${status}`;
             div.onclick = () => {
                 selectStreamVideo(v);
                 document.getElementById('streamLibraryModal').classList.add('hidden');
             };
             list.appendChild(div);
        });
    } catch(e) {}
}

function selectStreamVideo(video) {
    selectedStreamVideo = video;

    // Update Source Bar
    const thumb = document.getElementById('selectedVideoThumb');
    const icon = document.getElementById('selectedVideoIcon');

    if (video.thumbnailS3Key) {
        thumb.src = `${API_URL}/videos/${video.id}/thumbnail`;
        thumb.classList.remove('hidden');
        icon.classList.add('hidden');
    } else {
        thumb.classList.add('hidden');
        icon.classList.remove('hidden');
    }

    document.getElementById('studioSelectedTitle').innerText = video.title;

    // Auto-fill Metadata if empty
    if (!document.getElementById('streamMetaTitle').value) {
        document.getElementById('streamMetaTitle').value = video.title.replace(/\.[^/.]+$/, "");
    }

    // Preview
    const player = document.getElementById('previewPlayer');
    document.getElementById('previewPlaceholder').classList.add('hidden');
    player.classList.remove('hidden');
    player.src = `${API_URL}/library/stream/${video.id}`;

    player.onloadedmetadata = () => {
        console.log(`Video Loaded: ${player.videoWidth}x${player.videoHeight}`);
    };

    player.load();

    // Check Optimization
    if (video.optimizationStatus !== 'COMPLETED') {
        showConfirmModal("Optimize Required", "This video needs optimization for smooth streaming.",
            () => openOptimizeModal(video)
        );
    }
}

/* --- OPTIMIZATION MODAL --- */
function openOptimizeModal(video = null) {
    const targetVideo = video || selectedStreamVideo;
    if (!targetVideo) return showToast("No video selected", "error");

    document.getElementById('optimizeFileName').value = targetVideo.title;
    document.getElementById('optimizeModal').classList.remove('hidden');
}

// Renamed from app.js generic optimizeVideo to avoid conflict
function openOptimizeModalByName(filename) {
    document.getElementById('optimizeFileName').value = filename;
    document.getElementById('optimizeModal').classList.remove('hidden');
}

function selectOptimizeMode(mode, el) {
    document.getElementById('optimizeMode').value = mode;
    document.querySelectorAll('.platform-option').forEach(e => e.classList.remove('selected'));
    el.classList.add('selected');
}

async function submitOptimization() {
    const fileName = document.getElementById('optimizeFileName').value;
    const mode = document.getElementById('optimizeMode').value;
    const quality = document.getElementById('optimizeQuality').value;

    document.getElementById('optimizeModal').classList.add('hidden');
    showToast("Optimization started...", "info");

    try {
        const res = await apiFetch(`${API_URL}/convert/optimize?fileName=${encodeURIComponent(fileName)}&mode=${mode}&height=${quality}`, { method: 'POST' });
        if (res.ok) {
            showToast("Optimization queued. It will appear in library soon.", "success");
            // If in Library view, reload
            if(!document.getElementById('view-library').classList.contains('hidden')) {
                loadLibraryVideos();
            }
        } else {
            showToast("Failed to start optimization", "error");
        }
    } catch(e) { showToast("Error", "error"); }
}

/* --- STREAM CONTROL --- */
async function submitJob() {
    // Gather destinations
    const selectedKeys = destinations.filter(d => d.selected).map(d => d.key);

    if(!selectedStreamVideo) return showToast("Select a video source first", "error");
    if(selectedKeys.length === 0) return showToast("Select at least one destination", "error");

    // Check optimization strictness? User requirement says "intercept... prompting".
    if (selectedStreamVideo.optimizationStatus !== 'COMPLETED') {
        openOptimizeModal(selectedStreamVideo);
        return;
    }

    const btn = document.getElementById('btnGoLive');
    btn.disabled = true;
    btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Initializing...';

    const loopInfinite = document.getElementById('streamLoopInfinite').checked;
    const loopCount = loopInfinite ? -1 : document.getElementById('streamLoopCount').value;

    const fd = new FormData();
    selectedKeys.forEach(k => fd.append("streamKey", k));
    fd.append("videoKey", selectedStreamVideo.s3Key);
    fd.append("loopCount", loopCount);

    // Music
    const musicUpload = document.getElementById('uploadedStreamMusicName').value;
    const musicStock = document.getElementById('selectedStreamStockId').value;
    const musicMyLib = document.getElementById('selectedMyLibMusicName').value;
    const musicVol = (document.getElementById('streamAudioVol').value / 100).toFixed(1);

    // Detect active music tab logic in Studio UI
    if (!document.getElementById('streamAudioUploadSection').classList.contains('hidden') && musicUpload) {
        fd.append("musicName", musicUpload);
        fd.append("musicVolume", musicVol);
    } else if (!document.getElementById('streamAudioMyLibSection').classList.contains('hidden') && musicMyLib) {
        fd.append("musicName", musicMyLib);
        fd.append("musicVolume", musicVol);
    } else if (!document.getElementById('streamAudioLibSection').classList.contains('hidden') && musicStock) {
        fd.append("musicName", "stock:" + musicStock);
        fd.append("musicVolume", musicVol);
    }

    // Watermark
    if(window.uploadedWatermarkFile) {
        fd.append("watermarkFile", window.uploadedWatermarkFile);
    }

    fd.append("muteVideoAudio", document.getElementById('streamMuteOriginal').checked);

    // Metadata
    const title = document.getElementById('streamMetaTitle').value;
    const desc = document.getElementById('streamMetaDesc').value;
    const privacy = document.getElementById('streamMetaPrivacy').value;

    if (title) fd.append("title", title);
    if (desc) fd.append("description", desc);
    if (privacy) fd.append("privacy", privacy);

    // Stream Settings
    const orientation = document.getElementById('streamOrientation').value || "original";
    const quality = document.getElementById('streamQuality').value || "0";

    fd.append("streamMode", orientation);
    fd.append("streamQuality", quality);

    try {
        const res = await apiFetch(`${API_URL}/start`, {method:'POST', body:fd});
        const data = await res.json();
        if(data.success) {
            showToast("Stream Started!", "success");
            // Update UI
            document.getElementById('studioLiveBadge').classList.remove('hidden');
            checkStreamStatus();
        } else {
            showToast(data.message, "error");
        }
    } catch(e) { showToast("Failed to start", "error"); }
    finally {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-tower-broadcast"></i> Go Live';
    }
}

/* --- API HELPER --- */
async function apiFetch(url, options = {}) {
    try {
        const res = await fetch(url, options);
        if (res.status === 401 || res.status === 403) {
            const body = await res.clone().json().catch(() => ({}));
            if (body.message && (body.message.includes("YouTube") || body.message.includes("connected"))) {
                document.getElementById('addChannelModal')?.classList.remove('hidden');
                showToast("Please connect your YouTube channel.", "error");
            } else {
                window.location.href = '/login';
            }
            throw new Error("Authentication required");
        }
        return res;
    } catch (e) { throw e; }
}

/* --- USER & CHANNELS --- */
async function fetchUserInfo() {
    try {
        const res = await apiFetch(`/api/user-info?_=${new Date().getTime()}`);
        const data = await res.json();
        if (data.email) {
            currentUser = data;
            const avatar = document.getElementById('userAvatarSmall');
            const settingsIcon = document.getElementById('settingsIcon');
            if(avatar) {
                avatar.src = data.picture;
                avatar.classList.remove('hidden');
                settingsIcon.classList.add('hidden');
            }
            if(data.plan) renderPlanInfo(data.plan);
            if(data.enabled === false) document.getElementById('verificationBanner').classList.remove('hidden');
            checkInitialStatus();
        } else {
            window.location.href = '/login';
        }
    } catch(e) {}
}

let userChannels = [];
let selectedChannelId = null;

async function loadUserChannels() {
    try {
        const res = await apiFetch(`${API_URL}/channels`);
        userChannels = await res.json();
        renderChannelList(userChannels);
        renderChannelDropdown(userChannels);
    } catch(e) {}
}

function renderChannelList(channels) {
    const sidebarList = document.getElementById('channelIconsList');
    if(sidebarList) {
        sidebarList.innerHTML = '';
        channels.forEach((c, idx) => {
            const btn = document.createElement('button');
            btn.className = 'icon-btn';
            if(idx === 0) btn.classList.add('active');
            btn.innerHTML = `<img src="${c.profileUrl}" title="${c.name}">`;
            btn.onclick = () => {
                document.querySelectorAll('.icon-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                filterViewByChannel(c);
            };
            sidebarList.appendChild(btn);
        });
    }
    const settingsList = document.getElementById('channelListSettings');
    if(settingsList) {
        settingsList.innerHTML = '';
        channels.forEach(c => {
            settingsList.innerHTML += `
                <div class="queue-item">
                    <div class="queue-thumb"><img src="${c.profileUrl}" style="width:100%;height:100%;object-fit:cover;border-radius:4px;"></div>
                    <div style="flex:1"><b>${c.name}</b><div style="font-size:0.8rem;color:#666">${c.platform}</div></div>
                    <button class="btn btn-sm btn-danger" onclick="removeChannel(${c.id})"><i class="fa-solid fa-trash"></i></button>
                </div>
            `;
        });
    }
    const modalList = document.getElementById('modalChannelSelector');
    if(modalList) {
        modalList.innerHTML = '';
        channels.forEach((c, idx) => {
             const div = document.createElement('div');
             div.className = 'channel-icon-select';
             if(idx === 0) { div.classList.add('selected'); selectedChannelId = c.id; }
             div.innerHTML = `<img src="${c.profileUrl}" title="${c.name}">`;
             div.onclick = () => {
                 document.querySelectorAll('.channel-icon-select').forEach(el => el.classList.remove('selected'));
                 div.classList.add('selected');
                 selectedChannelId = c.id;
             };
             modalList.appendChild(div);
        });
    }
}

function filterViewByChannel(channel) {
    const nameEl = document.getElementById('currentChannelName');
    if(nameEl) nameEl.innerText = channel.name;
    showToast(`Switched to ${channel.name}`, 'info');
}

function renderChannelDropdown(channels) {
    const menu = document.getElementById('channelDropdownMenu');
    if(!menu) return;
    menu.innerHTML = '<div class="dropdown-item" onclick="filterViewByChannel({name:\'All Channels\'})">All Channels</div>';
    channels.forEach(c => {
        const safeName = c.name.replace(/'/g, "\\'");
        menu.innerHTML += `<div class="dropdown-item" onclick="filterViewByChannel({name:'${safeName}'})"><img src="${c.profileUrl}"> ${c.name}</div>`;
    });
}

function toggleChannelDropdown() {
    const menu = document.getElementById('channelDropdownMenu');
    if(menu) menu.classList.toggle('show');
}

let selectedPlatform = 'YOUTUBE';

function openConnectModal() {
    document.getElementById('connectChannelModal').classList.remove('hidden');
    selectPlatform('YOUTUBE', document.querySelector('.platform-option.selected'));
}

function selectPlatform(platform, el) {
    selectedPlatform = platform;
    document.querySelectorAll('#connectChannelModal .platform-option').forEach(e => e.classList.remove('selected'));
    el.classList.add('selected');

    const manualInput = document.getElementById('manualChannelInput');
    const btn = document.getElementById('btnConnect');

    if (platform === 'YOUTUBE') {
        manualInput.classList.add('hidden');
        btn.innerText = 'Connect with Google';
    } else {
        manualInput.classList.remove('hidden');
        btn.innerText = 'Connect ' + platform.charAt(0) + platform.slice(1).toLowerCase();
        document.getElementById('connectChannelName').focus();
    }
}

async function submitConnectChannel() {
    if (selectedPlatform === 'YOUTUBE') {
        window.location.href = '/oauth2/authorization/google-youtube?action=connect_youtube';
        return;
    }
    const name = document.getElementById('connectChannelName').value;
    if (!name) return showToast("Please enter a channel handle", "error");

    const btn = document.getElementById('btnConnect');
    btn.disabled = true;
    btn.innerText = "Connecting...";

    try {
        const res = await apiFetch(`${API_URL}/channels`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: name, platform: selectedPlatform })
        });
        if (res.ok) {
            showToast(selectedPlatform + " Channel Connected!", "success");
            document.getElementById('connectChannelModal').classList.add('hidden');
            loadUserChannels();
        } else {
            const data = await res.json();
            showToast(data.message || "Connection failed", "error");
        }
    } catch (e) {
        showToast("Error connecting channel", "error");
    } finally {
        btn.disabled = false;
        btn.innerText = "Connect";
    }
}

/* --- PUBLISHING --- */
function showScheduleModal() {
    document.getElementById('scheduleModal').classList.remove('hidden');
    const cat = document.getElementById('scheduleCategory');
    if(cat.options.length === 0) {
        apiFetch(`${API_URL}/youtube/categories`)
            .then(r=>r.json())
            .then(data => {
                 data.forEach(c => {
                     if(c.snippet.assignable) {
                         cat.innerHTML += `<option value="${c.id}">${c.snippet.title}</option>`;
                     }
                 });
            }).catch(()=>{});
    }
}

function closeScheduleModal() {
    document.getElementById('scheduleModal').classList.add('hidden');
}

function handleFileSelect(file) {
    if(!file) return;
    document.getElementById('mediaPlaceholder').classList.add('hidden');
    document.getElementById('selectedFileDisplay').classList.remove('hidden');
    document.getElementById('fileName').innerText = file.name;
    document.getElementById('thumbnailTools').classList.remove('hidden');

    const titleInput = document.getElementById('scheduleTitle');
    if(!titleInput.value) titleInput.value = file.name.replace(/\.[^/.]+$/, "");
    checkShortsCriteria(file);
}

function checkShortsCriteria(file) {
    const video = document.createElement('video');
    video.preload = 'metadata';
    video.onloadedmetadata = function() {
        window.URL.revokeObjectURL(video.src);
        const duration = video.duration;
        const w = video.videoWidth;
        const h = video.videoHeight;

        if (duration < 60 && w > h) {
            showConfirmModal("Convert to Short?",
                "This video is under 60 seconds but in Landscape. Convert to Vertical (9:16) for YouTube Shorts?",
                () => convertToShort(file.name)
            );
        } else if (w < h && duration < 60) {
            setPreviewMode('shorts');
            showToast("Detected Short format", "info");
        }
    }
    video.src = URL.createObjectURL(file);
}

async function convertToShort(filename) {
    showToast("Starting conversion...", "info");
    try {
        const res = await apiFetch(`${API_URL}/convert/shorts?fileName=${encodeURIComponent(filename)}`, { method: 'POST' });
        const data = await res.json();
        if (data.success) {
            showToast("Conversion started. Check library shortly.", "success");
        }
    } catch(e) { showToast("Conversion failed", "error"); }
}

function setPreviewMode(mode) {
    const img = document.getElementById('previewImageMock');
    const overlay = document.getElementById('shortsSafeZone');
    const btns = document.querySelectorAll('.toggle-group button');

    btns.forEach(b => {
        b.classList.remove('btn-primary');
        b.classList.add('btn-outline');
    });

    if (mode === 'shorts') {
        img.classList.add('shorts-mode');
        overlay.classList.remove('hidden');
        btns[1].classList.add('btn-primary');
        btns[1].classList.remove('btn-outline');
    } else {
        img.classList.remove('shorts-mode');
        overlay.classList.add('hidden');
        btns[0].classList.add('btn-primary');
        btns[0].classList.remove('btn-outline');
    }
}

function handleThumbnailSelect(e) {
    const file = e.target.files[0];
    if (file) setThumbnailPreview(file);
}

function setThumbnailPreview(file) {
    const reader = new FileReader();
    reader.onload = (e) => {
        document.getElementById('thumbPreview').innerHTML = `<img src="${e.target.result}" style="width:100%;height:100%;object-fit:cover;">`;
    };
    reader.readAsDataURL(file);
}

function extractFrame() {
    const file = document.getElementById('scheduleFile').files[0];
    if (!file) return showToast("No video selected", "error");

    const video = document.getElementById('frameExtractorVideo');
    const url = URL.createObjectURL(file);

    video.src = url;
    video.currentTime = 5;

    video.onseeked = () => {
        const canvas = document.createElement('canvas');
        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        canvas.toBlob((blob) => {
            const thumbFile = new File([blob], "thumbnail.jpg", { type: "image/jpeg" });
            const dt = new DataTransfer();
            dt.items.add(thumbFile);
            document.getElementById('scheduleThumbnail').files = dt.files;
            setThumbnailPreview(thumbFile);
            showToast("Thumbnail extracted!", "success");
            URL.revokeObjectURL(url);
        }, 'image/jpeg', 0.9);
    };
    video.load();
}

async function submitSchedule() {
    const file = document.getElementById('scheduleFile').files[0];
    const title = document.getElementById('scheduleTitle').value;
    const time = document.getElementById('scheduleTime').value;

    if((!file && !selectedLibraryVideoId) || !title || !time) return showToast("Please fill Title, Time and select a Video.", "error");

    const btn = document.getElementById('btnSchedule');
    btn.disabled = true;
    btn.innerText = "Uploading...";

    const formData = new FormData();
    if (selectedLibraryVideoId) formData.append("libraryVideoId", selectedLibraryVideoId);
    else formData.append("file", file);

    formData.append("title", title);
    formData.append("scheduledTime", time);
    formData.append("description", document.getElementById('scheduleDescription').value);
    formData.append("privacyStatus", document.getElementById('schedulePrivacy').value);
    formData.append("categoryId", document.getElementById('scheduleCategory').value);
    formData.append("tags", document.getElementById('scheduleTags').value);
    if(selectedChannelId) formData.append("socialChannelId", selectedChannelId);

    const firstComment = document.getElementById('scheduleFirstComment').value;
    if(firstComment) formData.append("firstComment", firstComment);

    const audioFile = document.getElementById('scheduleAudio').files[0];
    const audioTrackId = document.getElementById('selectedAudioTrackId').value;

    if (audioFile || audioTrackId) {
        const volPercent = document.getElementById('scheduleAudioVol').value;
        const vol = (volPercent / 100).toFixed(1);
        if (audioFile) formData.append("audioFile", audioFile);
        if (audioTrackId) formData.append("audioTrackId", audioTrackId);
        formData.append("audioVolume", vol);
    }

    const pContainer = document.getElementById('uploadProgressContainer');
    const pBar = document.getElementById('uploadProgressBar');
    const pText = document.getElementById('uploadPercent');
    pContainer.classList.remove('hidden');

    const xhr = new XMLHttpRequest();
    xhr.open("POST", `${API_URL}/videos/schedule`, true);

    xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
            const percent = Math.round((e.loaded / e.total) * 100);
            pBar.style.width = percent + "%";
            pText.innerText = percent + "%";
        }
    };

    xhr.onload = () => {
        btn.disabled = false;
        btn.innerText = "Schedule Post";
        pContainer.classList.add('hidden');
        if(xhr.status === 200) {
            showToast("Video Scheduled!", "success");
            closeScheduleModal();
            loadScheduledQueue();
        } else {
            showToast("Upload Failed", "error");
        }
    };
    xhr.onerror = () => { btn.disabled = false; showToast("Network Error", "error"); };
    xhr.send(formData);
}

async function loadScheduledQueue() {
    const list = document.getElementById('queueList');
    if(!list) return;
    try {
        const res = await apiFetch(`${API_URL}/videos`);
        const videos = await res.json();
        list.innerHTML = '';
        if(videos.length === 0) { list.innerHTML = `<div class="empty-state">No posts in queue.</div>`; return; }
        videos.forEach(v => {
            const statusClass = v.status === 'UPLOADED' ? 'color:#00875a' : (v.status === 'FAILED' ? 'color:#e02424' : 'color:#6b778c');
            const thumbUrl = v.thumbnailS3Key ? `${API_URL}/videos/${v.id}/thumbnail` : null;
            const thumbHtml = thumbUrl ? `<img src="${thumbUrl}" style="width:100%;height:100%;object-fit:cover;">` : `<i class="fa-solid fa-film"></i>`;
            list.innerHTML += `
                <div class="queue-item">
                    <div class="queue-thumb">${thumbHtml}</div>
                    <div style="flex:1"><div style="font-weight:600">${v.title}</div><div style="font-size:0.85rem; color:#666">Scheduled: ${new Date(v.scheduledTime).toLocaleString()}</div></div>
                    <div style="font-size:0.85rem; font-weight:600; ${statusClass}">${v.status}</div>
                </div>`;
        });
    } catch(e) {}
}

async function submitScheduledStream() {
    const selectedKeys = destinations.filter(d => d.selected).map(d => d.key);
    if(!selectedStreamVideo) return showToast("Please select a video source", "error");
    if(selectedKeys.length === 0) return showToast("Please select at least one destination", "error");

    const scheduledTime = document.getElementById('streamScheduleTime').value;
    if(!scheduledTime) return showToast("Please select a start time", "error");

    const loopInfinite = document.getElementById('streamLoopInfinite').checked;
    const loopCount = loopInfinite ? -1 : document.getElementById('streamLoopCount').value;

    const payload = {
        videoKey: selectedStreamVideo.s3Key,
        streamKeys: selectedKeys,
        scheduledTime: scheduledTime,
        loopCount: loopCount,
        streamMode: "original", // Default to original for now
        muteVideoAudio: document.getElementById('streamMuteOriginal').checked
    };

    const title = document.getElementById('streamMetaTitle').value;
    if (title) payload.title = title;

    // Music logic
    const musicUpload = document.getElementById('uploadedStreamMusicName').value;
    const musicStock = document.getElementById('selectedStreamStockId').value;
    const musicMyLib = document.getElementById('selectedMyLibMusicName').value;
    const musicVol = (document.getElementById('streamAudioVol').value / 100).toFixed(1);

    if (!document.getElementById('streamAudioUploadSection').classList.contains('hidden') && musicUpload) {
        payload.musicName = musicUpload; payload.musicVolume = musicVol;
    } else if (!document.getElementById('streamAudioMyLibSection').classList.contains('hidden') && musicMyLib) {
        payload.musicName = musicMyLib; payload.musicVolume = musicVol;
    } else if (!document.getElementById('streamAudioLibSection').classList.contains('hidden') && musicStock) {
        payload.musicName = "stock:" + musicStock; payload.musicVolume = musicVol;
    }

    try {
        const res = await apiFetch(`${API_URL}/stream/schedule`, {
            method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload)
        });
        if(res.ok) showToast("Stream Scheduled!", "success");
        else showToast("Scheduling failed", "error");
    } catch(e) { showToast("Error scheduling", "error"); }
}

async function cancelScheduledStream(id) {
    if(!confirm("Cancel this stream?")) return;
    try {
        await apiFetch(`${API_URL}/stream/scheduled/${id}`, { method: 'DELETE' });
        showScheduledStreamsModal();
    } catch(e) { showToast("Failed to cancel", "error"); }
}

async function stopStream() {
    showConfirmModal("End All Streams", "Are you sure you want to stop all active streams?", async () => {
        try {
            await apiFetch(`${API_URL}/stop`, {method:'POST'});
            checkStreamStatus();
            showToast("Streams Stopped", "info");
        } catch(e) {}
    });
}

async function stopStreamById(id) {
    if(!confirm("Stop this stream?")) return;
    try {
        await apiFetch(`${API_URL}/stop?streamId=${id}`, { method: 'POST' });
        checkStreamStatus();
        showToast("Stream Stopped", "success");
    } catch(e) { showToast("Failed to stop stream", "error"); }
}

function renderActiveStreams(streams) {
    // 1. Render to 'Streams' Tab in Studio
    const listStudio = document.getElementById('activeStreamsList');
    const emptyState = document.getElementById('streamsEmptyState');

    if (listStudio) {
        listStudio.innerHTML = '';
        if (!streams || streams.length === 0) {
            if(emptyState) emptyState.classList.remove('hidden');
        } else {
            if(emptyState) emptyState.classList.add('hidden');
            streams.forEach(s => {
                // Calculate duration if startTime available
                let timeText = "Live now";
                if(s.startTime) {
                    const start = new Date(s.startTime);
                    const now = new Date();
                    const diffMs = now - start;
                    const diffMins = Math.floor(diffMs / 60000);
                    const hrs = Math.floor(diffMins / 60);
                    const mins = diffMins % 60;
                    timeText = `${hrs}h ${mins}m`;
                }

                const div = document.createElement('div');
                div.className = 'activity-item'; // Reuse activity style
                div.innerHTML = `
                    <div class="act-icon" style="background:#e3f2fd; color:red;"><i class="fa-solid fa-satellite-dish"></i></div>
                    <div class="act-content">
                        <div style="font-weight:600; color:#333;">${s.title || "Live Stream"}</div>
                        <div style="font-size:0.8rem; color:#666;">
                            <i class="fa-regular fa-clock"></i> ${timeText} • ${s.streamKey.substring(0, 15)}...
                        </div>
                    </div>
                    <button class="btn btn-sm btn-outline" style="color:var(--danger); border-color:#ffdce0;" onclick="stopStreamById(${s.id})">
                        <i class="fa-solid fa-stop"></i> End
                    </button>
                `;
                listStudio.appendChild(div);
            });
        }
    }

    // Studio UI update (Badge)
    const badge = document.getElementById('studioLiveBadge');
    if (badge) {
        if (streams && streams.length > 0) badge.classList.remove('hidden');
        else badge.classList.add('hidden');
    }
}

/* --- DESTINATIONS --- */
let destinations = [];
function loadDestinations() {
    const saved = localStorage.getItem('afk_destinations');
    if(saved) destinations = JSON.parse(saved);
    renderDestinations();
}

function openAddDestinationChoiceModal() {
    const list = document.getElementById('ytChannelList');
    list.innerHTML = '';
    const ytChannels = userChannels.filter(c => c.platform === 'YOUTUBE');

    if (ytChannels.length > 0) {
        document.getElementById('defaultYtFetchOption').classList.add('hidden');
        ytChannels.forEach(c => {
            const div = document.createElement('div');
            div.className = 'queue-item';
            div.style.cssText = 'cursor: pointer; border: 2px solid var(--primary); background: #f0f7ff; margin-bottom: 10px;';
            div.onclick = () => connectYouTubeDestination(c.id);
            div.innerHTML = `<div style="background: white; border-radius: 50%; width: 40px; height: 40px; display: flex; align-items: center; justify-content: center;"><img src="${c.profileUrl}" style="width:100%; height:100%; border-radius:50%;"></div><div style="flex: 1;"><div style="font-weight: 700; font-size: 1rem; color: var(--primary-dark);">Fetch for ${c.name}</div></div>`;
            list.appendChild(div);
        });
        const addNewDiv = document.createElement('div');
        addNewDiv.className = 'queue-item';
        addNewDiv.style.cssText = 'cursor: pointer; background: #fff; border: 1px dashed #ccc; margin-bottom: 15px; justify-content: center; color: var(--primary);';
        addNewDiv.innerHTML = '<i class="fa-solid fa-plus"></i> Connect another YouTube Channel';
        addNewDiv.onclick = () => window.location.href = '/oauth2/authorization/google-youtube?action=connect_youtube';
        list.appendChild(addNewDiv);
    } else {
        document.getElementById('defaultYtFetchOption').classList.remove('hidden');
    }
    document.getElementById('destChoiceModal').classList.remove('hidden');
}

function openManualDestinationModal() {
    document.getElementById('destChoiceModal').classList.add('hidden');
    document.getElementById('newDestName').value = '';
    document.getElementById('newDestKey').value = '';
    document.getElementById('addDestinationModal').classList.remove('hidden');
    document.getElementById('newDestName').focus();
}

async function connectYouTubeDestination(channelId = null) {
    document.getElementById('destChoiceModal').classList.add('hidden');
    showToast("Connecting to YouTube...", "info");
    try {
        let url = `${API_URL}/youtube/key`;
        if (channelId) url += `?channelId=${channelId}`;
        const res = await fetch(url);
        if (res.status === 401) { window.location.href = '/oauth2/authorization/google-youtube?action=connect_youtube'; return; }
        const data = await res.json();
        if (res.ok && data.key) {
            const existing = destinations.find(d => d.key === data.key);
            if(existing) return showToast("Destination exists", "info");
            const newId = Date.now();
            destinations.push({ id: newId, name: data.name || "YouTube (Auto)", key: data.key, type: 'youtube_auto', selected: true });
            saveDestinations();
            renderDestinations();
            showToast("YouTube Connected!", "success");
        } else {
            showToast(data.message || "Failed", "error");
        }
    } catch (e) { showToast("Error", "error"); }
}

function submitDestination() {
    const name = document.getElementById('newDestName').value;
    const key = document.getElementById('newDestKey').value;
    const editId = document.getElementById('addDestinationModal').dataset.editId;
    if(!name || !key) return showToast("Fill all fields", "error");

    if (editId) {
        const idx = destinations.findIndex(d => d.id == editId);
        if (idx !== -1) { destinations[idx].name = name; destinations[idx].key = key; }
        delete document.getElementById('addDestinationModal').dataset.editId;
    } else {
        destinations.push({ id: Date.now(), name, key, selected: true });
    }
    saveDestinations();
    renderDestinations();
    document.getElementById('addDestinationModal').classList.add('hidden');
}

function removeDestination(id, e) {
    e.stopPropagation();
    showConfirmModal("Remove Destination", "Delete this key?", () => {
        destinations = destinations.filter(d => d.id !== id);
        saveDestinations();
        renderDestinations();
    });
}

function saveDestinations() { localStorage.setItem('afk_destinations', JSON.stringify(destinations)); }

function renderDestinations() {
    const list = document.getElementById('destinationList');
    if(!list) return;
    list.innerHTML = '';
    if(destinations.length === 0) { list.innerHTML = `<div class="empty-state" style="padding:10px; font-size:0.8rem;">No destinations.</div>`; return; }

    destinations.forEach(d => {
        const div = document.createElement('div');
        div.className = 'destination-item';
        if (d.selected) div.classList.add('active');
        div.onclick = () => toggleDestination(d.id);

        let icon = d.type === 'youtube_auto' ? '<i class="fa-brands fa-youtube" style="color:red;margin-right:5px"></i>' : '';
        const editBtn = d.type === 'youtube_auto' ? '' : `<button class="btn btn-sm btn-text" onclick="editDestination(${d.id}, event)"><i class="fa-solid fa-pen"></i></button>`;

        div.innerHTML = `
            <div class="dest-icon" style="color:${d.selected?'var(--primary)':'#999'}"><i class="fa-solid ${d.selected?'fa-circle-check':'fa-circle'}"></i></div>
            <div style="flex:1; display:flex; align-items:center; font-weight:600; font-size:0.9rem;">${icon} ${d.name}</div>
            <div>${editBtn}<button class="btn btn-sm btn-text" onclick="removeDestination(${d.id}, event)"><i class="fa-solid fa-trash"></i></button></div>
        `;
        list.appendChild(div);
    });
}

function toggleDestination(id) {
    const dest = destinations.find(d => d.id === id);
    if(dest) { dest.selected = !dest.selected; saveDestinations(); renderDestinations(); }
}

function editDestination(id, e) {
    e.stopPropagation();
    const dest = destinations.find(d => d.id === id);
    document.getElementById('newDestName').value = dest.name;
    document.getElementById('newDestKey').value = dest.key;
    document.getElementById('addDestinationModal').dataset.editId = id;
    document.getElementById('addDestinationModal').classList.remove('hidden');
}

function toggleStreamKeyVisibility(inputId, btn) {
    const input = document.getElementById(inputId);
    const icon = btn.querySelector('i');
    if (input.type === 'password') { input.type = 'text'; icon.classList.replace('fa-eye', 'fa-eye-slash'); }
    else { input.type = 'password'; icon.classList.replace('fa-eye-slash', 'fa-eye'); }
}

function removeChannel(id) {
    showConfirmModal("Disconnect Channel", "Remove channel?", async () => {
        try {
            await apiFetch(`${API_URL}/channels/${id}`, { method: 'DELETE' });
            loadUserChannels();
        } catch(e) { showToast("Error", "error"); }
    });
}

/* --- TIMER & STATUS POLL --- */
let statusPollInterval = null;
function startStatusPoll() {
    if(statusPollInterval) clearInterval(statusPollInterval);
    checkStreamStatus();
    statusPollInterval = setInterval(checkStreamStatus, 5000);
}

async function checkStreamStatus() {
    try {
        const res = await apiFetch(`${API_URL}/status`);
        const data = await res.json();
        if(data.success) {
            renderActiveStreams(data.data.activeStreams || []);
            updateStudioState(data.data.activeStreams || []);
        }
    } catch(e) {}
}

function updateStudioState(streams) {
    const btn = document.getElementById('btnGoLive');
    const badge = document.getElementById('studioLiveBadge');

    // Check if ANY stream is active (simplified for now)
    const isLive = streams.some(s => s.live);

    if (isLive) {
        if (btn) {
            btn.innerText = "End Broadcast";
            btn.classList.remove('btn-danger');
            btn.classList.add('btn-secondary');
            btn.onclick = stopStream;
        }
        if (badge) badge.classList.remove('hidden');

        // Find active stream ID to stop specifically if needed, but stopStream() handles all via prompt
    } else {
        if (btn) {
            btn.innerText = "Go Live";
            btn.classList.remove('btn-secondary');
            btn.classList.add('btn-danger');
            btn.onclick = submitJob;
        }
        if (badge) badge.classList.add('hidden');
    }
}

async function checkInitialStatus() { startStatusPoll(); }
async function checkYoutubeStatus() { try { await apiFetch(`${API_URL}/channels`); } catch(e){} }
function loadGlobalSettings() { loadDestinations(); }

/* --- LIBRARY --- */
let selectedLibraryVideos = new Set();
let libraryPagination = { page: 1, limit: 20, total: 0, data: [] };

async function loadLibraryVideos() {
    const list = document.getElementById('libraryList');
    selectedLibraryVideos.clear();
    updateMergeButton();
    try {
        const res = await apiFetch(`${API_URL}/library`);
        const result = await res.json();
        libraryPagination.data = result.data || [];
        libraryPagination.total = libraryPagination.data.length;
        libraryPagination.page = 1;
        renderLibraryPage();
    } catch(e){ list.innerHTML = '<div class="empty-state">Failed to load library.</div>'; }
}

function renderLibraryPage() {
    const list = document.getElementById('libraryList');
    list.innerHTML = '';
    if (libraryPagination.total === 0) { list.innerHTML = '<div class="empty-state">Library is empty.</div>'; renderPaginationControls(); return; }

    const start = (libraryPagination.page - 1) * libraryPagination.limit;
    const end = start + libraryPagination.limit;
    const pageItems = libraryPagination.data.slice(start, end);

    let totalSize = 0;
    libraryPagination.data.forEach(v => totalSize += (v.fileSize || 0));
    const totalSizeMB = (totalSize / 1024 / 1024).toFixed(2);

    const statsDiv = document.createElement('div');
    statsDiv.style.cssText = "padding:10px; font-weight:600; color:#666; border-bottom:1px solid #eee; margin-bottom:10px; display:flex; justify-content:space-between;";
    statsDiv.innerHTML = `<span>Total: ${totalSizeMB} MB</span> <span>${libraryPagination.total} Videos</span>`;
    list.appendChild(statsDiv);

    pageItems.forEach(v => {
        const sizeMB = v.fileSize ? (v.fileSize / 1024 / 1024).toFixed(2) + ' MB' : 'Unknown';
        const div = document.createElement('div');
        div.className = 'queue-item';

        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox'; checkbox.style.marginRight = '10px'; checkbox.className = 'lib-chk'; checkbox.dataset.title = v.title;
        if(selectedLibraryVideos.has(v.title)) checkbox.checked = true;
        checkbox.onchange = (e) => {
            if(e.target.checked) selectedLibraryVideos.add(v.title); else selectedLibraryVideos.delete(v.title);
            updateMergeButton();
        };

        let optimizeBtn = '';
        if (v.optimizationStatus === 'COMPLETED') optimizeBtn = `<span class="badge" style="background:#00875a; color:white; margin-right:5px;">OPTIMIZED</span>`;
        else if (v.optimizationStatus === 'IN_PROGRESS') optimizeBtn = `<i class="fa-solid fa-spinner fa-spin" style="margin-right:5px"></i>`;
        else optimizeBtn = `<button class="btn btn-sm btn-text" onclick="optimizeVideo('${v.title}')" title="Optimize"><i class="fa-solid fa-wand-magic-sparkles"></i></button>`;

        div.innerHTML = `
            <div class="queue-thumb" onclick="openPreviewModal(${v.id})"><i class="fa-solid fa-file-video"></i></div>
            <div style="flex:1"><div style="font-weight:600; cursor:pointer" onclick="openPreviewModal(${v.id})">${v.title}</div><div style="font-size:0.8rem; color:#888;">${sizeMB}</div></div>
            <div>${optimizeBtn}<button class="btn btn-sm btn-text" onclick="scheduleFromLibrary(${v.id}, '${v.title.replace(/'/g, "\\'")}')"><i class="fa-regular fa-calendar-plus"></i></button><button class="btn btn-sm btn-text" onclick="deleteLibraryVideo(${v.id}, '${v.title}')"><i class="fa-solid fa-trash"></i></button></div>
        `;
        div.prepend(checkbox);
        list.appendChild(div);
    });
    renderPaginationControls();
}

function renderPaginationControls() {
    const container = document.getElementById('libraryPaginationControls');
    if(!container) return;
    const totalPages = Math.ceil(libraryPagination.total / libraryPagination.limit);
    if(totalPages <= 1) { container.innerHTML = ''; return; }
    container.innerHTML = `<button class="btn btn-sm btn-outline" ${libraryPagination.page===1?'disabled':''} onclick="changeLibraryPage(-1)"><</button> Page ${libraryPagination.page} of ${totalPages} <button class="btn btn-sm btn-outline" ${libraryPagination.page===totalPages?'disabled':''} onclick="changeLibraryPage(1)">></button>`;
}

function changeLibraryPage(d) { libraryPagination.page += d; renderLibraryPage(); }

function toggleSelectAllLibrary(source) {
    document.querySelectorAll('.lib-chk').forEach(cb => {
        cb.checked = source.checked;
        if(source.checked) selectedLibraryVideos.add(cb.dataset.title); else selectedLibraryVideos.delete(cb.dataset.title);
    });
    updateMergeButton();
}

function updateMergeButton() {
    const btnMerge = document.getElementById('btnMerge');
    const btnDel = document.getElementById('btnDeleteSelected');
    if(btnMerge) btnMerge.disabled = selectedLibraryVideos.size < 2;
    if(btnDel) { btnDel.disabled = selectedLibraryVideos.size === 0; btnDel.innerText = selectedLibraryVideos.size > 0 ? `Delete (${selectedLibraryVideos.size})` : "Delete Selected"; }
}

async function deleteSelectedVideos() {
    if(selectedLibraryVideos.size === 0) return;
    showConfirmModal("Delete Multiple?", `Delete ${selectedLibraryVideos.size} videos?`, async () => {
        const titles = Array.from(selectedLibraryVideos);
        const videosToDelete = libraryPagination.data.filter(v => titles.includes(v.title));
        for (let v of videosToDelete) { try { await apiFetch(`${API_URL}/library/${v.id}`, { method: 'DELETE' }); } catch(e) {} }
        selectedLibraryVideos.clear();
        loadLibraryVideos();
        fetchUserInfo();
    });
}

function openPreviewModal(id) {
    const video = document.getElementById('mainPreviewVideo');
    video.src = `${API_URL}/library/stream/${id}`;
    document.getElementById('previewModal').classList.remove('hidden');
    video.play();
}

let selectedLibraryVideoId = null;
function scheduleFromLibrary(id, title) {
    selectedLibraryVideoId = id;
    showScheduleModal();
    document.getElementById('mediaPlaceholder').classList.add('hidden');
    document.getElementById('selectedFileDisplay').classList.remove('hidden');
    document.getElementById('fileName').innerText = "Library: " + title;
    document.getElementById('scheduleTitle').value = title.replace(/\.[^/.]+$/, "");
}

function optimizeVideo(filename) { openOptimizeModalByName(filename); }

function openYouTubeImportModal() { document.getElementById('youtubeImportModal').classList.remove('hidden'); }
async function submitYouTubeImport() {
    const url = document.getElementById('ytImportUrl').value;
    if(!url) return;
    try { await apiFetch(`${API_URL}/library/import-youtube`, { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({url}) }); showToast("Import started", "success"); setTimeout(loadLibraryVideos, 2000); } catch(e){}
}

async function mergeSelectedVideos() {
    if(selectedLibraryVideos.size < 2) return;
    const files = Array.from(selectedLibraryVideos);
    showConfirmModal("Merge Videos", "Merge selected?", async () => {
        try { await apiFetch(`${API_URL}/library/merge`, { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({files}) }); showToast("Merge started", "success"); } catch(e){}
    });
}

async function deleteLibraryVideo(id, filename) {
    showConfirmModal("Delete", `Delete ${filename}?`, async () => {
        try { await apiFetch(`${API_URL}/library/${id}`, { method: 'DELETE' }); loadLibraryVideos(); fetchUserInfo(); } catch(e){}
    });
}

async function handleBulkUpload(e) {
    const files = e.target.files;
    if(!files.length) return;
    const fd = new FormData();
    for(let f of files) fd.append("files", f);
    try { await apiFetch(`${API_URL}/library/upload`, {method:'POST', body:fd}); loadLibraryVideos(); fetchUserInfo(); } catch(e){}
}

function showAutoScheduleModal() { document.getElementById('autoScheduleModal').classList.remove('hidden'); }
async function submitAutoSchedule() {
    // ... basic auto schedule logic
    const startDate = document.getElementById('autoStartDate').value;
    const slots = document.getElementById('autoTimeSlots').value;
    try {
        await apiFetch(`${API_URL}/library/auto-schedule`, {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({startDate, timeSlots: slots.split(','), useAi: document.getElementById('autoUseAi').checked})
        });
        showToast("Auto schedule started", "success");
    } catch(e){}
}

/* --- AI --- */
async function aiGenerate(type) {
    const ctx = document.getElementById('scheduleTitle').value || "Video";
    const target = type === 'description' ? document.getElementById('scheduleDescription') : null;
    if(!target) return;
    target.placeholder = "AI is writing...";
    try {
        const res = await apiFetch(`${API_URL}/ai/generate`, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({type, context: ctx}) });
        const data = await res.json();
        target.value = data.result;
    } catch(e) {}
}

/* --- SETTINGS --- */
function renderPlanInfo(plan) {
    document.getElementById('planName').innerText = plan.name;
    const usedMB = (plan.storageUsed / 1024 / 1024).toFixed(1);
    const limitMB = (plan.storageLimit / 1024 / 1024).toFixed(0);
    document.getElementById('storageText').innerText = `${usedMB}/${limitMB} MB`;
    document.getElementById('storageBar').style.width = Math.min(100, (plan.storageUsed/plan.storageLimit)*100) + "%";
}

async function cancelSubscription() {
    showConfirmModal("Cancel", "Downgrade to free?", async () => {
        try { await apiFetch(`${API_URL}/pricing/cancel`, { method: 'POST' }); window.location.reload(); } catch(e){}
    });
}

function switchSettingsTab(tabName) {
    document.querySelectorAll('.settings-section').forEach(el => el.classList.add('hidden'));
    document.getElementById(`tab-${tabName}`).classList.remove('hidden');
    if(tabName === 'plans') loadInternalPricing();
    if(tabName === 'benefits') loadBenefits();
}

function loadBenefits() {
    if(!currentUser || !currentUser.plan) return;
    document.getElementById('benefitsContent').innerHTML = `<h3>${currentUser.plan.name}</h3>`;
}

async function loadInternalPricing() {
    const grid = document.getElementById('internalPlanGrid');
    if(!grid) return;
    grid.innerHTML = "Loading...";
    try {
        const res = await apiFetch('/api/pricing?country=US');
        const data = await res.json();
        grid.innerHTML = '';
        data.plans.forEach(plan => {
            grid.innerHTML += `<div class="plan-card"><h3>${plan.title}</h3><div class="plan-price">${plan.price}</div><button class="btn btn-primary" onclick="openPaymentModal('${plan.id}','${plan.title}','${plan.price}')">Upgrade</button></div>`;
        });
    } catch(e) { grid.innerHTML = "Failed"; }
}

/* --- MODALS --- */
function openSupportModal() { document.getElementById('supportModal').classList.remove('hidden'); }
let confirmCallback = null;
function showConfirmModal(title, message, onConfirm) {
    document.getElementById('confirmTitle').innerText = title;
    document.getElementById('confirmMessage').innerText = message;
    confirmCallback = onConfirm;
    document.getElementById('confirmationModal').classList.remove('hidden');
    document.getElementById('btnConfirmAction').onclick = () => { if(confirmCallback) confirmCallback(); closeConfirmModal(); };
}
function closeConfirmModal() { document.getElementById('confirmationModal').classList.add('hidden'); confirmCallback = null; }

let selectedPlanId = null;
function openPaymentModal(id, title, price) {
    selectedPlanId = id;
    document.getElementById('paymentPlanName').innerText = title;
    document.getElementById('paymentAmount').innerText = price;
    document.getElementById('paymentModal').classList.remove('hidden');
}
async function processPayment() {
    try { await apiFetch('/api/pricing/upgrade', { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({ planId: selectedPlanId }) }); window.location.reload(); } catch(e){}
}

/* --- ENGAGEMENT --- */
async function showEngagementSettings() {
    try {
        const res = await apiFetch(`${API_URL}/engagement/settings`);
        const data = await res.json();
        document.getElementById('engAutoReply').checked = data.autoReplyEnabled;
        document.getElementById('engagementSettingsModal').classList.remove('hidden');
    } catch(e){}
}
async function saveEngagementSettings() {
    try {
        await apiFetch(`${API_URL}/engagement/settings`, { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({ autoReplyEnabled: document.getElementById('engAutoReply').checked }) });
        document.getElementById('engagementSettingsModal').classList.add('hidden');
    } catch(e){}
}

/* --- ANALYTICS/CALENDAR --- */
async function initAnalytics() {
    // Basic stub, real chart logic requires Chart.js which is loaded
    const ctx = document.getElementById('analyticsChart');
    if(ctx && window.Chart) {
        // ... chart init ...
    }
}
function initCalendar() {
    const el = document.getElementById('calendar');
    if(el && window.FullCalendar && !el.innerHTML) {
        // ... calendar init ...
    }
}

/* --- COMMUNITY --- */
let currentComments = [];

async function loadComments() {
    // Support both Main Engagement View and Studio Chat Tab
    const listMain = document.getElementById('threadList');
    const listStudio = document.getElementById('studioChatList');

    if(!listMain && !listStudio) return;

    try {
        const res = await apiFetch(`${API_URL}/comments`);
        const data = await res.json();
        currentComments = data.items || [];

        // Initial Render
        renderComments(currentComments);
    } catch(e) {
        if(listMain) listMain.innerHTML = '<div class="empty-state">Failed to load comments</div>';
        if(listStudio) listStudio.innerHTML = '<div class="empty-state">Failed to load comments</div>';
    }
}

function filterCommTab(tab) {
    // Nav
    document.querySelectorAll('.comm-nav-item').forEach(el => el.classList.remove('active'));
    document.querySelectorAll('.comm-nav-item').forEach(el => {
        if(el.getAttribute('onclick').includes(`'${tab}'`)) el.classList.add('active');
    });

    // Filter
    let filtered = [];
    if (tab === 'all') {
        filtered = currentComments;
    } else if (tab === 'unreplied') {
        filtered = currentComments.filter(c => c.snippet.totalReplyCount === 0);
    } else if (tab === 'activity') {
        filtered = currentComments;
    }

    renderComments(filtered);
}

function renderComments(comments) {
    // Render to Main Engagement View
    const listMain = document.getElementById('threadList');
    if(listMain) {
        listMain.innerHTML = '';
        if(!comments || comments.length === 0) {
            listMain.innerHTML = '<div class="empty-state">No conversations found.</div>';
        } else {
            comments.forEach(thread => {
                const top = thread.snippet.topLevelComment.snippet;
                const div = document.createElement('div');
                div.className = 'thread-item';
                div.onclick = () => openThread(thread);
                div.innerHTML = `
                    <img src="${top.authorProfileImageUrl}" class="thread-avatar">
                    <div style="flex:1; overflow:hidden;">
                        <div class="thread-meta"><b>${top.authorDisplayName}</b> • ${new Date(top.publishedAt).toLocaleDateString()}</div>
                        <div class="thread-preview">${top.textDisplay}</div>
                    </div>
                `;
                listMain.appendChild(div);
            });
        }
    }

    // Render to Studio Chat Tab
    const listStudio = document.getElementById('studioChatList');
    const emptyState = document.getElementById('chatEmptyState');
    if(listStudio) {
        listStudio.innerHTML = '';
        if(!comments || comments.length === 0) {
            if(emptyState) emptyState.classList.remove('hidden');
        } else {
            if(emptyState) emptyState.classList.add('hidden');
            comments.forEach(thread => {
                const top = thread.snippet.topLevelComment.snippet;
                const div = document.createElement('div');
                div.className = 'sidebar-thread-item';
                // Simplified view for sidebar
                div.innerHTML = `
                    <img src="${top.authorProfileImageUrl}">
                    <div class="sidebar-thread-content">
                        <div class="sidebar-thread-name">${top.authorDisplayName}</div>
                        <div class="sidebar-thread-msg">${top.textDisplay}</div>
                    </div>
                `;
                listStudio.appendChild(div);
            });
        }
    }
}


function openThread(thread) {
    const top = thread.snippet.topLevelComment.snippet;
    document.getElementById('emptyThreadState').classList.add('hidden');
    document.getElementById('activeThread').classList.remove('hidden');

    // Thread Header
    document.getElementById('threadVideoTitle').innerText = "Comment on Video (ID: " + thread.snippet.videoId + ")"; // Title not available in comment object easily
    document.getElementById('threadVideoLink').href = `https://youtube.com/watch?v=${thread.snippet.videoId}&lc=${thread.id}`;

    const messages = document.getElementById('threadMessages');
    messages.innerHTML = '';

    // Render Top Level
    messages.innerHTML += `
        <div class="msg-bubble" style="display:flex; gap:10px; margin-bottom:20px;">
             <img src="${top.authorProfileImageUrl}" style="width:32px; height:32px; border-radius:50%;">
             <div>
                 <div style="font-weight:600; font-size:0.85rem;">${top.authorDisplayName}</div>
                 <div style="background:#f1f2f4; padding:10px; border-radius: 0 10px 10px 10px; margin-top:2px; font-size:0.9rem;">${top.textDisplay}</div>
             </div>
        </div>
    `;

    // In a real app, we would fetch replies here if not included or if truncated
    if(thread.replies) {
        thread.replies.comments.forEach(r => {
             messages.innerHTML += `
                <div class="msg-bubble reply" style="display:flex; gap:10px; margin-bottom:15px; margin-left:40px;">
                     <img src="${r.snippet.authorProfileImageUrl}" style="width:24px; height:24px; border-radius:50%;">
                     <div>
                         <div style="font-weight:600; font-size:0.8rem;">${r.snippet.authorDisplayName}</div>
                         <div style="background:#e3f2fd; padding:8px; border-radius: 0 10px 10px 10px; margin-top:2px; font-size:0.9rem;">${r.snippet.textDisplay}</div>
                     </div>
                </div>
            `;
        });
    }

    // Set context for reply
    document.getElementById('replyInput').dataset.parentId = thread.id;
}

async function sendReply() {
    const parentId = document.getElementById('replyInput').dataset.parentId;
    const text = document.getElementById('replyInput').value;
    if(!text) return;

    try {
        const res = await apiFetch(`${API_URL}/comments/${parentId}/reply`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({text})
        });
        if(res.ok) {
            showToast("Reply sent", "success");
            document.getElementById('replyInput').value = '';
            // Ideally reload thread or append reply locally
        } else {
            showToast("Failed to reply", "error");
        }
    } catch(e) { showToast("Error sending reply", "error"); }
}

function toggleAiSuggestions() {
    const area = document.getElementById('aiSuggestionsArea');
    if(area.classList.contains('hidden')) {
        area.classList.remove('hidden');
        generateSuggestions();
    } else {
        area.classList.add('hidden');
    }
}

async function generateSuggestions() {
    const chips = document.getElementById('aiChips');
    chips.innerHTML = 'Generating...';
    // Mock call for now or use existing generic AI endpoint
    // In real scenario: call /api/ai/suggest-reply
    setTimeout(() => {
        chips.innerHTML = '';
        ['Thanks for watching! 😊', 'Glad you liked it!', 'New video coming soon!'].forEach(s => {
            const chip = document.createElement('div');
            chip.style.cssText = "background:white; border:1px solid #cce0ff; padding:5px 10px; borderRadius:15px; cursor:pointer; font-size:0.85rem; color:#0052cc;";
            chip.innerText = s;
            chip.onclick = () => document.getElementById('replyInput').value = s;
            chips.appendChild(chip);
        });
    }, 1000);
}
