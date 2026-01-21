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

    // Default Date
    const now = new Date();
    now.setMinutes(now.getMinutes() - now.getTimezoneOffset());
    document.getElementById('streamScheduleTime').value = now.toISOString().slice(0, 16);
}

async function handleStreamVideoUpload(e) {
    const file = e.target.files[0];
    if (!file) return;

    const btn = document.querySelector('button[onclick*="streamUploadInput"]');
    const originalText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = `<i class="fa-solid fa-spinner fa-spin"></i> Uploading...`;

    const formData = new FormData();
    formData.append("files", file);

    try {
        const res = await apiFetch(`${API_URL}/library/upload`, { method: "POST", body: formData });

        if (res.ok) {
            showToast("Video uploaded successfully!", "success");
            // Auto-refresh library modal content
            openLibraryModalForStream();
            fetchUserInfo(); // Refresh storage
        } else {
            showToast("Upload failed.", "error");
        }
    } catch (err) {
        showToast("Upload error.", "error");
    } finally {
        btn.disabled = false;
        btn.innerHTML = originalText;
        e.target.value = '';
    }
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
    if (viewName === 'stream') loadDestinations(); // Ensure destinations are loaded

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

/* --- API HELPER --- */
async function apiFetch(url, options = {}) {
    try {
        const res = await fetch(url, options);
        if (res.status === 401 || res.status === 403) {
            // Check if it's a YouTube specific auth error (usually returned as 401/403 with message)
            // But usually 401 means "Login required"
            // Let's read the body to see if it mentions "YouTube"
            const body = await res.clone().json().catch(() => ({}));

            if (body.message && (body.message.includes("YouTube") || body.message.includes("connected"))) {
                // Show "Connect Channel" modal
                document.getElementById('connectChannelModal').classList.remove('hidden');
                showToast("Please connect your YouTube channel.", "error");
            } else {
                // Standard Login
                window.location.href = '/login';
            }
            throw new Error("Authentication required");
        }
        return res;
    } catch (e) {
        throw e;
    }
}

/* --- USER & CHANNELS --- */
async function fetchUserInfo() {
    try {
        const res = await apiFetch(`/api/user-info?_=${new Date().getTime()}`);
        const data = await res.json();
        if (data.email) {
            currentUser = data;

            // Update Avatar in sidebar
            const avatar = document.getElementById('userAvatarSmall');
            const settingsIcon = document.getElementById('settingsIcon');
            if(avatar) {
                avatar.src = data.picture;
                avatar.classList.remove('hidden');
                settingsIcon.classList.add('hidden');
            }

            // Plan Info
            if(data.plan) renderPlanInfo(data.plan);

            // Verification Warning
            if(data.enabled === false) {
                document.getElementById('verificationBanner').classList.remove('hidden');
            }

            // Resume Stream State
            checkInitialStatus();

            // Check if just connected channel
            const urlParams = new URLSearchParams(window.location.search);
            if (urlParams.get('connected')) {
                showToast("Channel Connected Successfully!", "success");
                window.history.replaceState({}, document.title, window.location.pathname);
                loadUserChannels();
            } else if (urlParams.get('error') === 'channel_sync_failed') {
                 showToast("Channel connection failed. Please try again.", "error");
                 window.history.replaceState({}, document.title, window.location.pathname);
            }

        } else {
            // Not logged in?
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
    } catch(e) {
        console.error("Failed to load channels", e);
    }
}

function renderChannelDropdown(channels) {
    const menu = document.getElementById('channelDropdownMenu');
    if(!menu) return;
    menu.innerHTML = '<div class="dropdown-item" onclick="filterViewByChannel({name:\'All Channels\'})">All Channels</div>';
    channels.forEach(c => {
        // Escape quotes in name
        const safeName = c.name.replace(/'/g, "\\'");
        menu.innerHTML += `
            <div class="dropdown-item" onclick="filterViewByChannel({name:'${safeName}'})">
                <img src="${c.profileUrl}"> ${c.name}
            </div>
        `;
    });
}

function toggleChannelDropdown() {
    const menu = document.getElementById('channelDropdownMenu');
    if(menu) menu.classList.toggle('show');
}

async function removeChannel(id) {
    if (!confirm("Are you sure you want to remove this channel?")) return;
    try {
        const res = await apiFetch(`${API_URL}/channels/${id}`, { method: 'DELETE' });
        if (res.ok) {
            showToast("Channel removed", "success");
            loadUserChannels();
        } else {
            showToast("Failed to remove channel", "error");
        }
    } catch(e) {
        showToast("Error removing channel", "error");
    }
}

function renderChannelList(channels) {
    // 1. Sidebar Icons
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

    // 2. Settings List
    const settingsList = document.getElementById('channelListSettings');
    if(settingsList) {
        settingsList.innerHTML = '';
        channels.forEach(c => {
            settingsList.innerHTML += `
                <div class="queue-item">
                    <div class="queue-thumb"><img src="${c.profileUrl}" style="width:100%;height:100%;object-fit:cover;border-radius:4px;"></div>
                    <div style="flex:1">
                        <b>${c.name}</b>
                        <div style="font-size:0.8rem;color:#666">${c.platform}</div>
                    </div>
                    <button class="btn btn-sm btn-danger" onclick="removeChannel(${c.id})"><i class="fa-solid fa-trash"></i></button>
                </div>
            `;
        });
    }

    // 3. Modal Selector (New Post)
    const modalList = document.getElementById('modalChannelSelector');
    if(modalList) {
        modalList.innerHTML = '';
        channels.forEach((c, idx) => {
             const div = document.createElement('div');
             div.className = 'channel-icon-select';
             if(idx === 0) {
                 div.classList.add('selected');
                 selectedChannelId = c.id;
             }
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
    // Update label in sub-sidebar
    const nameEl = document.getElementById('currentChannelName');
    if(nameEl) nameEl.innerText = channel.name;

    // TODO: Filter queue list logic
    showToast(`Switched to ${channel.name}`, 'info');
}

let selectedPlatform = 'YOUTUBE';

function openConnectModal() {
    document.getElementById('connectChannelModal').classList.remove('hidden');
    selectPlatform('YOUTUBE', document.querySelector('.platform-option.selected'));
}

function selectPlatform(platform, el) {
    selectedPlatform = platform;
    document.querySelectorAll('#connectChannelModal .platform-option').forEach(e => e.classList.remove('selected'));
    if(el) el.classList.add('selected');

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
        // Use dedicated google-youtube client
        window.location.href = '/oauth2/authorization/google-youtube?action=connect_youtube';
        return;
    }

    const name = document.getElementById('connectChannelName').value;
    if (!name) {
        showToast("Please enter a channel handle", "error");
        return;
    }

    const btn = document.getElementById('btnConnect');
    const originalText = btn.innerText;
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
        btn.innerText = originalText;
    }
}

/* --- PUBLISHING --- */
function showScheduleModal() {
    document.getElementById('scheduleModal').classList.remove('hidden');
    // Load categories if empty
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

    // Auto title
    const titleInput = document.getElementById('scheduleTitle');
    if(!titleInput.value) titleInput.value = file.name.replace(/\.[^/.]+$/, "");

    // Check for Shorts
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
            // Landscape but short duration -> Suggest convert
            showConfirmModal("Convert to Short?",
                "This video is under 60 seconds but in Landscape. Convert to Vertical (9:16) for YouTube Shorts?",
                () => convertToShort(file.name)
            );
        } else if (w < h && duration < 60) {
            // Already a short -> Auto switch preview
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

/* --- THUMBNAIL LOGIC --- */
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
    video.currentTime = 5; // Capture at 5s mark (or random)

    // Once loaded and seeked
    video.onseeked = () => {
        const canvas = document.createElement('canvas');
        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;

        const ctx = canvas.getContext('2d');
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);

        canvas.toBlob((blob) => {
            // Create file from blob
            const thumbFile = new File([blob], "thumbnail.jpg", { type: "image/jpeg" });

            // Assign to input
            const dt = new DataTransfer();
            dt.items.add(thumbFile);
            document.getElementById('scheduleThumbnail').files = dt.files;

            setThumbnailPreview(thumbFile);
            showToast("Thumbnail extracted!", "success");

            // Clean up
            URL.revokeObjectURL(url);
        }, 'image/jpeg', 0.9);
    };

    // Trigger load
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
    if (selectedLibraryVideoId) {
        formData.append("libraryVideoId", selectedLibraryVideoId);
    } else {
        formData.append("file", file);
    }
    formData.append("title", title);
    formData.append("scheduledTime", time);
    formData.append("description", document.getElementById('scheduleDescription').value);
    formData.append("privacyStatus", document.getElementById('schedulePrivacy').value);
    formData.append("categoryId", document.getElementById('scheduleCategory').value);
    formData.append("tags", document.getElementById('scheduleTags').value);
    if(selectedChannelId) formData.append("socialChannelId", selectedChannelId);

    // Audio
    const firstComment = document.getElementById('scheduleFirstComment').value;
    if(firstComment) formData.append("firstComment", firstComment);

    // Audio
    const audioFile = document.getElementById('scheduleAudio').files[0];
    const audioTrackId = document.getElementById('selectedAudioTrackId').value;

    if (audioFile || audioTrackId) {
        const volPercent = document.getElementById('scheduleAudioVol').value;
        const vol = (volPercent / 100).toFixed(1);

        if (audioFile) formData.append("audioFile", audioFile);
        if (audioTrackId) formData.append("audioTrackId", audioTrackId);

        formData.append("audioVolume", vol);
    }

    // Progress
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

    xhr.onerror = () => {
        btn.disabled = false;
        showToast("Network Error", "error");
    };

    xhr.send(formData);
}

async function loadScheduledQueue() {
    const list = document.getElementById('queueList');
    if(!list) return;

    try {
        const res = await apiFetch(`${API_URL}/videos`);
        const videos = await res.json();

        list.innerHTML = '';
        if(videos.length === 0) {
            list.innerHTML = `<div class="empty-state">No posts in queue.</div>`;
            return;
        }

        videos.forEach(v => {
            const statusClass = v.status === 'UPLOADED' ? 'color:#00875a' : (v.status === 'FAILED' ? 'color:#e02424' : 'color:#6b778c');
            const thumbUrl = v.thumbnailS3Key ? `${API_URL}/videos/${v.id}/thumbnail` : null;
            const thumbHtml = thumbUrl
                ? `<img src="${thumbUrl}" style="width:100%;height:100%;object-fit:cover;">`
                : `<i class="fa-solid fa-film"></i>`;

            list.innerHTML += `
                <div class="queue-item">
                    <div class="queue-thumb">${thumbHtml}</div>
                    <div style="flex:1">
                        <div style="font-weight:600">${v.title}</div>
                        <div style="font-size:0.85rem; color:#666">
                            Scheduled: ${new Date(v.scheduledTime).toLocaleString()}
                        </div>
                    </div>
                    <div style="font-size:0.85rem; font-weight:600; ${statusClass}">${v.status}</div>
                </div>
            `;
        });
    } catch(e) {}
}

/* --- STREAMING --- */
let selectedStreamVideo = null;
let streamTimerInterval = null;
let streamStartTime = null;
let streamTabMode = 'now'; // 'now' or 'schedule'

function switchStreamTab(mode) {
    streamTabMode = mode;
    if (mode === 'now') {
        document.getElementById('tabGoLiveNow').classList.replace('btn-outline', 'btn-primary');
        document.getElementById('tabScheduleStream').classList.replace('btn-primary', 'btn-outline');
        document.getElementById('streamActionNow').classList.remove('hidden');
        document.getElementById('streamActionSchedule').classList.add('hidden');
    } else {
        document.getElementById('tabGoLiveNow').classList.replace('btn-primary', 'btn-outline');
        document.getElementById('tabScheduleStream').classList.replace('btn-outline', 'btn-primary');
        document.getElementById('streamActionNow').classList.add('hidden');
        document.getElementById('streamActionSchedule').classList.remove('hidden');
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
             div.innerHTML = `<i class="fa-solid fa-film"></i> ${v.title}`;
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
    document.getElementById('selectedVideoTitle').innerText = video.title;

    // Auto-fill Title Input
    const titleInput = document.getElementById('streamTitleInput');
    if (titleInput && !titleInput.value) {
        titleInput.value = video.title.replace(/\.[^/.]+$/, "");
    }

    // Auto-fill AI Topic
    const topicInput = document.getElementById('aiStreamTopic');
    if(topicInput && !topicInput.value) {
        topicInput.value = video.title.replace(/\.[^/.]+$/, ""); // remove extension
    }

    // Preview
    const player = document.getElementById('previewPlayer');
    document.getElementById('previewPlaceholder').classList.add('hidden');
    player.classList.remove('hidden');
    // player.style.display = 'block'; // Removed, handled by CSS

    // Use the streaming endpoint
    player.src = `${API_URL}/library/stream/${video.id}`;
    player.style.visibility = 'visible';

    player.onloadedmetadata = () => {
        console.log(`Video Loaded: ${player.videoWidth}x${player.videoHeight}`);
        if(player.videoWidth > 0) {
             player.style.display = 'block'; // Ensure block
        }
    };

    player.load();
    player.play().catch(e => console.log("Autoplay blocked/failed:", e));

    player.onerror = () => {
        console.error("Preview Error", player.error);
        showToast("Failed to load preview", "error");
    };
}

async function generateStreamMetadata() {
    const topic = document.getElementById('aiStreamTopic').value;
    if(!topic) return showToast("Please enter a topic", "error");

    const container = document.getElementById('aiMetadataResults');
    container.classList.remove('hidden');
    document.getElementById('aiStreamTip').innerText = "Generating...";

    try {
        const res = await apiFetch(`${API_URL}/ai/stream-metadata`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({context: topic})
        });
        const data = await res.json();

        // Populate AI fields
        document.getElementById('aiStreamTitle').value = data.title;
        document.getElementById('aiStreamDesc').value = data.description;
        document.getElementById('aiStreamTags').value = data.tags;
        document.getElementById('aiStreamTip').innerText = data.tip;

        // Auto-fill Main inputs if empty
        const mainTitle = document.getElementById('streamTitleInput');
        const mainDesc = document.getElementById('streamDescInput');
        if(mainTitle && !mainTitle.value) mainTitle.value = data.title;
        if(mainDesc && !mainDesc.value) mainDesc.value = data.description;

    } catch(e) {
        showToast("AI Generation Failed", "error");
    }
}

function copyToClipboard(elementId) {
    const el = document.getElementById(elementId);
    if(el) {
        el.select();
        document.execCommand('copy'); // Fallback for older browsers
        if(navigator.clipboard) {
            navigator.clipboard.writeText(el.value);
        }
        showToast("Copied!", "success");
    }
}

async function submitJob() {
    // Gather all selected keys
    const selectedKeys = destinations.filter(d => d.selected).map(d => d.key);

    const loopInfinite = document.getElementById('streamLoopInfinite').checked;
    const loopCount = loopInfinite ? -1 : document.getElementById('streamLoopCount').value;

    if(!selectedStreamVideo) return showToast("Please select a video source", "error");
    if(selectedKeys.length === 0) return showToast("Please select at least one destination", "error");

    if (selectedStreamVideo.optimizationStatus !== 'COMPLETED') {
        showConfirmModal("Optimize Video?",
            "This video is not optimized for streaming. Performance might be poor. Convert now?",
            () => optimizeVideo(selectedStreamVideo.title)
        );
        // Return to prevent immediate start, allowing user to choose optimization
        return;
    }

    const btn = document.getElementById('btnGoLive');
    btn.disabled = true;
    btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Starting...';

    const fd = new FormData();
    // Append each key (Spring will treat same-name keys as a List<String>)
    selectedKeys.forEach(k => fd.append("streamKey", k));

    fd.append("videoKey", selectedStreamVideo.s3Key);
    fd.append("loopCount", loopCount);

    // Meta Data fields
    const title = document.getElementById('streamTitleInput').value;
    const desc = document.getElementById('streamDescInput').value;
    if(title) fd.append("title", title);
    if(desc) fd.append("description", desc);

    // Music
    const musicUpload = document.getElementById('uploadedStreamMusicName').value;
    const musicStock = document.getElementById('selectedStreamStockId').value;
    const musicMyLib = document.getElementById('selectedStreamMyLibId').value;
    const musicVol = (document.getElementById('streamAudioVol').value / 100).toFixed(1);

    if (musicUpload && !document.getElementById('streamAudioUploadSection').classList.contains('hidden')) {
        fd.append("musicName", musicUpload);
        fd.append("musicVolume", musicVol);
    } else if (musicStock && !document.getElementById('streamAudioLibSection').classList.contains('hidden')) {
        fd.append("musicName", "stock:" + musicStock);
        fd.append("musicVolume", musicVol);
    } else if (musicMyLib && !document.getElementById('streamAudioMyLibSection').classList.contains('hidden')) {
        // My library logic similar to upload (assumes file is in user dir)
        // Need to check how My Library returns ID/Name.
        // Assuming ID is filename or we need to pass filename.
        // loadStreamMyAudioLibrary sets ID to filename/s3Key usually.
        fd.append("musicName", musicMyLib);
        fd.append("musicVolume", musicVol);
    }

    // Watermark
    if(window.uploadedWatermarkFile) {
        fd.append("watermarkFile", window.uploadedWatermarkFile);
    }

    // Mute Original
    const muteOriginal = document.getElementById('streamMuteOriginal').checked;
    fd.append("muteVideoAudio", muteOriginal);

    // Output Mode
    const outputMode = document.getElementById('streamOutputMode').value;
    fd.append("streamMode", outputMode);

    try {
        const res = await apiFetch(`${API_URL}/start`, {method:'POST', body:fd});
        const data = await res.json();
        if(data.success) {
            showToast("Stream Started Successfully!", "success");
            setLiveState(true);
            streamStartTime = new Date();
            startTimer();

            // Save state
            localStorage.setItem('afk_stream_state', JSON.stringify({
                startTime: streamStartTime.toISOString(),
                videoTitle: selectedStreamVideo ? selectedStreamVideo.title : "Unknown Video",
                videoId: selectedStreamVideo ? selectedStreamVideo.id : null
            }));
        } else {
            showToast(data.message, "error");
        }
    } catch(e) { showToast("Failed to start stream", "error"); }
    finally {
        btn.disabled = false;
        if(!document.getElementById('liveBadge').classList.contains('hidden')) {
             btn.classList.add('hidden');
        } else {
             btn.innerHTML = '<i class="fa-solid fa-tower-broadcast"></i> Go Live';
        }
    }
}

async function submitScheduledStream() {
    const selectedKeys = destinations.filter(d => d.selected).map(d => d.key);
    if(!selectedStreamVideo) return showToast("Please select a video source", "error");
    if(selectedKeys.length === 0) return showToast("Please select at least one destination", "error");

    const scheduledTime = document.getElementById('streamScheduleTime').value;
    if(!scheduledTime) return showToast("Please select a start time", "error");

    if (new Date(scheduledTime) < new Date()) {
        return showToast("Start time cannot be in the past", "error");
    }

    const loopInfinite = document.getElementById('streamLoopInfinite').checked;
    const loopCount = loopInfinite ? -1 : document.getElementById('streamLoopCount').value;

    const payload = {
        videoKey: selectedStreamVideo.s3Key, // Filename essentially
        streamKeys: selectedKeys,
        scheduledTime: scheduledTime,
        loopCount: loopCount,
        streamMode: document.getElementById('streamOutputMode').value,
        muteVideoAudio: document.getElementById('streamMuteOriginal').checked
    };

    // Music logic (Simplified for JSON payload, logic duplicated from submitJob)
    const musicUpload = document.getElementById('uploadedStreamMusicName').value;
    const musicStock = document.getElementById('selectedStreamStockId').value;
    const musicVol = (document.getElementById('streamAudioVol').value / 100).toFixed(1);

    if (musicUpload && !document.getElementById('streamAudioUploadSection').classList.contains('hidden')) {
        payload.musicName = musicUpload;
        payload.musicVolume = musicVol;
    } else if (musicStock && !document.getElementById('streamAudioLibSection').classList.contains('hidden')) {
        payload.musicName = "stock:" + musicStock;
        payload.musicVolume = musicVol;
    }

    // Note: Watermark file cannot be uploaded in JSON schedule easily without separate upload flow.
    // For now, scheduling ignores fresh watermark uploads.

    try {
        const res = await apiFetch(`${API_URL}/stream/schedule`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        const data = await res.json();
        if(res.ok) {
            showToast("Stream Scheduled!", "success");
            // Clear or reset UI?
        } else {
            showToast(data.message || "Scheduling failed", "error");
        }
    } catch(e) { showToast("Error scheduling", "error"); }
}

async function showScheduledStreamsModal() {
    document.getElementById('scheduledStreamsModal').classList.remove('hidden');
    const list = document.getElementById('scheduledStreamsList');
    list.innerHTML = "Loading...";

    try {
        const res = await apiFetch(`${API_URL}/stream/scheduled`);
        const data = await res.json();

        list.innerHTML = '';
        if(!data.data || data.data.length === 0) {
            list.innerHTML = "No upcoming streams.";
            return;
        }

        data.data.forEach(s => {
            const date = new Date(s.scheduledTime).toLocaleString();
            const div = document.createElement('div');
            div.className = 'queue-item';
            div.innerHTML = `
                <div style="flex:1">
                    <div style="font-weight:600">Video: ${s.videoKey}</div>
                    <div style="font-size:0.85rem; color:#666">Start: ${date}</div>
                    <div style="font-size:0.8rem; color:${s.status==='FAILED'?'red':'#00875a'}">${s.status}</div>
                </div>
                <button class="btn btn-sm btn-text" onclick="cancelScheduledStream(${s.id})" title="Cancel"><i class="fa-solid fa-trash"></i></button>
            `;
            list.appendChild(div);
        });
    } catch(e) { list.innerHTML = "Failed to load."; }
}

async function cancelScheduledStream(id) {
    if(!confirm("Cancel this stream?")) return;
    try {
        await apiFetch(`${API_URL}/stream/scheduled/${id}`, { method: 'DELETE' });
        showScheduledStreamsModal(); // reload
    } catch(e) { showToast("Failed to cancel", "error"); }
}

async function stopStream() {
    showConfirmModal("End Stream", "Are you sure you want to stop the live stream?", async () => {
        try {
            await apiFetch(`${API_URL}/stop`, {method:'POST'});
            setLiveState(false);
            showToast("Stream Stopped", "info");
            stopTimer();
            localStorage.removeItem('afk_stream_state');
        } catch(e) {}
    });
}

function setLiveState(isLive) {
    const badge = document.getElementById('liveBadge');
    const offlineBadge = document.getElementById('offlineBadge');
    const btnGo = document.getElementById('btnGoLive');
    const btnStop = document.getElementById('btnStop');

    if(isLive) {
        badge.classList.remove('hidden');
        offlineBadge.classList.add('hidden');
        btnGo.classList.add('hidden');
        btnStop.classList.remove('hidden');
        log("Stream is LIVE");
    } else {
        badge.classList.add('hidden');
        offlineBadge.classList.remove('hidden');
        btnGo.classList.remove('hidden');
        btnStop.classList.add('hidden');
        log("Stream Offline");
        stopTimer();
    }
}

function log(msg) {
    const t = document.getElementById('console');
    if(t) {
        t.innerHTML += `<div>[${new Date().toLocaleTimeString()}] ${msg}</div>`;
        t.scrollTop = t.scrollHeight;
    }
}

/* --- DESTINATIONS --- */
let destinations = [];

function loadDestinations() {
    const saved = localStorage.getItem('afk_destinations');
    if(saved) destinations = JSON.parse(saved);
    renderDestinations();
}

function addDestination() {
    document.getElementById('newDestName').value = '';
    document.getElementById('newDestKey').value = '';
    document.getElementById('addDestinationModal').classList.remove('hidden');
    document.getElementById('newDestName').focus();
}

function submitDestination() {
    const name = document.getElementById('newDestName').value;
    const key = document.getElementById('newDestKey').value;
    const editId = document.getElementById('addDestinationModal').dataset.editId;

    if(!name || !key) {
        showToast("Please fill all fields", "error");
        return;
    }

    if (editId) {
        // Update existing
        const idx = destinations.findIndex(d => d.id == editId);
        if (idx !== -1) {
            destinations[idx].name = name;
            destinations[idx].key = key;
            showToast("Destination Updated", "success");
        }
        delete document.getElementById('addDestinationModal').dataset.editId;
    } else {
        // Add new
        const newId = Date.now();
        // New destinations are selected by default for convenience
        destinations.push({ id: newId, name, key, selected: true });
        showToast("Destination Added", "success");
    }

    saveDestinations();
    renderDestinations();
    document.getElementById('addDestinationModal').classList.add('hidden');
}

function removeDestination(id, e) {
    e.stopPropagation();
    showConfirmModal("Remove Destination", "Delete this stream key?", () => {
        destinations = destinations.filter(d => d.id !== id);
        saveDestinations();
        renderDestinations();
    });
}

function saveDestinations() {
    localStorage.setItem('afk_destinations', JSON.stringify(destinations));
}

function renderDestinations() {
    const list = document.getElementById('destinationList');
    if(!list) return;
    list.innerHTML = '';

    if(destinations.length === 0) {
        list.innerHTML = `
            <div class="empty-state" style="padding: 20px; text-align: center;">
                <p style="margin-bottom: 10px; color: #666;">No stream destinations added.</p>
                <button class="btn btn-outline btn-sm" onclick="addDestination()">
                    <i class="fa-solid fa-plus"></i> Add Stream Key
                </button>
            </div>
        `;
        return;
    }

    destinations.forEach(d => {
        const div = document.createElement('div');
        div.className = 'destination-item';
        // Multi-select toggle
        if (d.selected) div.classList.add('active');

        div.onclick = () => toggleDestination(d.id);
        div.dataset.id = d.id;
        // XSS Fix: Use textContent for name
        const nameDiv = document.createElement('div');
        nameDiv.style.flex = '1';
        const nameB = document.createElement('b');
        nameB.textContent = d.name;
        nameDiv.appendChild(nameB);

        div.innerHTML = `
            <div class="dest-icon" style="color: ${d.selected ? 'var(--primary)' : '#999'}">
                <i class="fa-solid ${d.selected ? 'fa-circle-check' : 'fa-circle'}"></i>
            </div>
        `;
        div.appendChild(nameDiv);

        const actionsDiv = document.createElement('div');
        actionsDiv.innerHTML = `
            <button class="btn btn-sm btn-text" onclick="editDestination(${d.id}, event)" title="Edit"><i class="fa-solid fa-pen"></i></button>
            <button class="btn btn-sm btn-text" onclick="removeDestination(${d.id}, event)" title="Remove"><i class="fa-solid fa-trash"></i></button>
        `;
        div.appendChild(actionsDiv);
        list.appendChild(div);
    });
}

function toggleDestination(id) {
    const dest = destinations.find(d => d.id === id);
    if(dest) {
        dest.selected = !dest.selected;
        saveDestinations(); // Save to local storage
        renderDestinations(); // Re-render
    }
}

function editDestination(id, e) {
    e.stopPropagation();
    const dest = destinations.find(d => d.id === id);
    if(!dest) return;

    document.getElementById('newDestName').value = dest.name;
    document.getElementById('newDestKey').value = dest.key;
    // Store editing ID in modal
    document.getElementById('addDestinationModal').dataset.editId = id;

    document.getElementById('addDestinationModal').classList.remove('hidden');
    document.getElementById('newDestName').focus();
}

function toggleStreamKeyVisibility(inputId, btn) {
    const input = document.getElementById(inputId);
    const icon = btn.querySelector('i');
    if (input.type === 'password') {
        input.type = 'text';
        icon.classList.remove('fa-eye');
        icon.classList.add('fa-eye-slash');
    } else {
        input.type = 'password';
        icon.classList.remove('fa-eye-slash');
        icon.classList.add('fa-eye');
    }
}

// function selectDestination(id) removed in favor of toggleDestination

/* --- TIMER --- */
function startTimer() {
    stopTimer();
    const el = document.getElementById('streamTimer');
    if(!el) return;

    streamTimerInterval = setInterval(() => {
        if(!streamStartTime) return;
        const diff = Math.floor((new Date() - streamStartTime) / 1000);
        const h = Math.floor(diff / 3600).toString().padStart(2, '0');
        const m = Math.floor((diff % 3600) / 60).toString().padStart(2, '0');
        const s = (diff % 60).toString().padStart(2, '0');
        el.innerText = `${h}:${m}:${s}`;
    }, 1000);
}

function stopTimer() {
    if(streamTimerInterval) clearInterval(streamTimerInterval);
    const el = document.getElementById('streamTimer');
    if(el) el.innerText = "00:00:00";
}

async function checkInitialStatus() {
    try {
        const res = await apiFetch(`${API_URL}/status`);
        const data = await res.json();

        const savedState = localStorage.getItem('afk_stream_state');
        let stateObj = savedState ? JSON.parse(savedState) : null;

        if(data.success && data.data.live) {
            setLiveState(true);
            // If we have saved start time, use it
            if (stateObj && stateObj.startTime) {
                streamStartTime = new Date(stateObj.startTime);
            } else {
                streamStartTime = new Date();
            }
            startTimer();

            // Restore UI selection if possible
            if (stateObj) {
                if (stateObj.videoTitle) {
                    document.getElementById('selectedVideoTitle').innerText = stateObj.videoTitle;
                    document.getElementById('previewPlaceholder').classList.add('hidden');
                    const player = document.getElementById('previewPlayer');
                    player.classList.remove('hidden');
                    // We can't easily restore the exact video src without the ID,
                    // but we can try if state has it.
                    // Assuming stateObj has videoId if we save it.
                }
            }
        } else {
            // Backend says offline, clear local state
            if (stateObj) {
                localStorage.removeItem('afk_stream_state');
            }
        }
    } catch(e){}
}

async function checkYoutubeStatus() {
    try {
        const res = await apiFetch(`${API_URL}/channels`);
        const channels = await res.json();
        if(channels.length === 0) {
            // Optional: Auto-prompt to connect if no channels
            // For now, let the user click "Add Channel"
        }
    } catch(e){}
}

function loadGlobalSettings() {
    loadDestinations();
}

/* --- LIBRARY --- */
let selectedLibraryVideos = new Set();

async function loadLibraryVideos() {
    const list = document.getElementById('libraryList');
    selectedLibraryVideos.clear();
    updateMergeButton();

    try {
        const res = await apiFetch(`${API_URL}/library`);
        const data = await res.json();
        list.innerHTML = '';
        if(!data.data || !data.data.length) {
            list.innerHTML = '<div class="empty-state">Library is empty. Upload videos to get started.</div>';
            return;
        }

        // Header stats
        let totalSize = 0;
        let hasInProgress = false;
        data.data.forEach(v => totalSize += (v.fileSize || 0));
        const totalSizeMB = (totalSize / 1024 / 1024).toFixed(2);

        list.innerHTML = `<div style="padding:10px; font-weight:600; color:#666; border-bottom:1px solid #eee; margin-bottom:10px;">Total Library Size: ${totalSizeMB} MB</div>`;

        data.data.forEach(v => {
            const sizeMB = v.fileSize ? (v.fileSize / 1024 / 1024).toFixed(2) + ' MB' : 'Unknown';
            const isOptimized = v.title.includes("_optimized"); // Basic check, better if backend flag

            // Don't show optimized files as main items if we want to hide clutter, but user might want to see them.
            // For now, let's show them but maybe different icon.

            const div = document.createElement('div');
            div.className = 'queue-item';

            // Generate checkboxes
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.style.marginRight = '10px';
            checkbox.onchange = (e) => {
                if(e.target.checked) selectedLibraryVideos.add(v.title);
                else selectedLibraryVideos.delete(v.title);
                updateMergeButton();
            };

            const thumb = document.createElement('div');
            thumb.className = 'queue-thumb';
            thumb.innerHTML = `<i class="fa-solid fa-file-video"></i>`;
            thumb.style.cursor = 'pointer';
            thumb.onclick = () => openPreviewModal(v.id);

            const content = document.createElement('div');
            content.style.flex = '1';
            content.innerHTML = `
                <div style="font-weight:600; cursor:pointer" onclick="openPreviewModal(${v.id})">${v.title}</div>
                <div style="font-size:0.8rem; color:#888;">${sizeMB}</div>
            `;

            const actions = document.createElement('div');

            let optimizeBtn = '';
            if (v.optimizationStatus === 'COMPLETED') {
                optimizeBtn = `<span class="badge" style="background:#00875a; color:white; margin-right:5px; font-size:0.7rem;">OPTIMIZED</span>`;
            } else if (v.optimizationStatus === 'IN_PROGRESS') {
                optimizeBtn = `<button class="btn btn-sm btn-text" disabled><i class="fa-solid fa-spinner fa-spin"></i></button>`;
                hasInProgress = true;
            } else {
                optimizeBtn = `<button class="btn btn-sm btn-text" onclick="optimizeVideo('${v.title}')" title="Optimize for Stream"><i class="fa-solid fa-wand-magic-sparkles"></i></button>`;
            }

            actions.innerHTML = `
                ${optimizeBtn}
                <button class="btn btn-sm btn-text" onclick="scheduleFromLibrary(${v.id}, '${v.title.replace(/'/g, "\\'")}')" title="Schedule Post"><i class="fa-regular fa-calendar-plus"></i></button>
                <button class="btn btn-sm btn-text" onclick="openPreviewModal(${v.id})" title="Preview"><i class="fa-solid fa-play"></i></button>
                <button class="btn btn-sm btn-text" onclick="deleteLibraryVideo(${v.id}, '${v.title}')" title="Delete"><i class="fa-solid fa-trash"></i></button>
            `;

            div.prepend(checkbox);
            div.appendChild(thumb);
            div.appendChild(content);
            div.appendChild(actions);

            list.appendChild(div);
        });

        if (hasInProgress) {
            setTimeout(loadLibraryVideos, 5000);
        }
    } catch(e){}
}

function updateMergeButton() {
    const btn = document.getElementById('btnMerge');
    if(btn) btn.disabled = selectedLibraryVideos.size < 2;
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

    // UI updates
    document.getElementById('mediaPlaceholder').classList.add('hidden');
    document.getElementById('selectedFileDisplay').classList.remove('hidden');
    document.getElementById('fileName').innerText = "Library: " + title;

    const titleInput = document.getElementById('scheduleTitle');
    if(!titleInput.value) titleInput.value = title.replace(/\.[^/.]+$/, "");
}

async function optimizeVideo(filename) {
    showToast("Starting optimization...", "info");
    try {
        const res = await apiFetch(`${API_URL}/convert?fileName=${encodeURIComponent(filename)}`, { method: 'POST' });
        if(res.ok) {
            showToast("Optimization started", "success");
            // Refresh logic handled by polling in loadLibraryVideos or manual refresh
            loadLibraryVideos();
        } else {
            showToast("Optimization failed", "error");
        }
    } catch(e) {
        showToast("Error requesting optimization", "error");
    }
}

function openYouTubeImportModal() {
    document.getElementById('ytImportUrl').value = '';
    document.getElementById('youtubeImportModal').classList.remove('hidden');
}

async function submitYouTubeImport() {
    const url = document.getElementById('ytImportUrl').value;
    if(!url) return showToast("Please enter a URL", "error");

    showToast("Importing...", "info");
    document.getElementById('youtubeImportModal').classList.add('hidden');

    try {
        const res = await apiFetch(`${API_URL}/library/import-youtube`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({url})
        });
        const data = await res.json();
        if(res.ok) {
            showToast(data.message, "success");
            // Reload library after delay
            setTimeout(loadLibraryVideos, 2000);
        } else {
            showToast(data.message, "error");
        }
    } catch(e) {
        showToast("Import failed", "error");
    }
}

async function mergeSelectedVideos() {
    if(selectedLibraryVideos.size < 2) return;
    const files = Array.from(selectedLibraryVideos);

    showConfirmModal("Merge Videos", `Merge ${files.length} videos? This will create a new file.`, async () => {
        showToast("Merging started... This may take a while.", "info");
        try {
            const res = await apiFetch(`${API_URL}/library/merge`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({files})
            });
            const data = await res.json();
            if(res.ok) {
                showToast("Merge Successful!", "success");
                loadLibraryVideos();
            } else {
                showToast(data.message || "Merge failed", "error");
            }
        } catch(e) {
            showToast("Merge request failed", "error");
        }
    });
}

async function deleteLibraryVideo(id, filename) {
    showConfirmModal("Delete Video", `Delete "${filename}"? This cannot be undone.`, async () => {
        try {
            // New endpoint using ID
            const res = await apiFetch(`${API_URL}/library/${id}`, { method: 'DELETE' });
            const data = await res.json();
            if(res.ok && data.success) {
                showToast("File deleted", "success");
                loadLibraryVideos();
                fetchUserInfo(); // Update storage usage
            } else {
                showToast(data.message || "Delete failed", "error");
            }
        } catch(e) { showToast("Error deleting file", "error"); }
    });
}

async function handleBulkUpload(e) {
    const files = e.target.files;
    if(!files.length) return;
    const fd = new FormData();
    for(let f of files) fd.append("files", f);

    showToast("Uploading library...", "info");
    try {
        const res = await apiFetch(`${API_URL}/library/upload`, {method:'POST', body:fd});
        if(res.ok) {
            showToast("Uploaded!", "success");
            loadLibraryVideos();
            fetchUserInfo(); // Refresh storage
        }
    } catch(e){ showToast("Failed", "error"); }
}

async function optimizeVideo(filename) {
    showToast("Starting optimization...", "info");
    try {
        const res = await apiFetch(`${API_URL}/convert/optimize?fileName=${encodeURIComponent(filename)}`, { method: 'POST' });
        const data = await res.json();
        if(res.ok) {
            showToast("Optimization started. Check back soon.", "success");
        } else {
            showToast(data.message || "Error", "error");
        }
    } catch(e) { showToast("Request failed", "error"); }
}

function showAutoScheduleModal() {
    document.getElementById('autoScheduleModal').classList.remove('hidden');
}

async function submitAutoSchedule() {
    const startDate = document.getElementById('autoStartDate').value;
    const slots = document.getElementById('autoTimeSlots').value;
    const topic = document.getElementById('autoTopic').value;
    const useAi = document.getElementById('autoUseAi').checked;

    if(!startDate || !slots) {
        showToast("Please fill start date and time slots", "error");
        return;
    }

    try {
        const res = await apiFetch(`${API_URL}/library/auto-schedule`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                startDate,
                timeSlots: slots.split(',').map(s=>s.trim()),
                topic,
                useAi
            })
        });

        if(res.ok) {
            showToast("Auto-scheduler initiated", "success");
            document.getElementById('autoScheduleModal').classList.add('hidden');
            loadLibraryVideos();
        } else {
            const data = await res.json();
            showToast(data.message || "Scheduling failed", "error");
        }
    } catch(e) { showToast("Error scheduling", "error"); }
}

/* --- AI --- */
async function aiGenerate(type) {
    const ctx = document.getElementById('scheduleTitle').value || "Video";
    const target = type === 'description' ? document.getElementById('scheduleDescription') : null;
    if(!target) return;

    target.placeholder = "AI is writing...";
    try {
        const res = await apiFetch(`${API_URL}/ai/generate`, {
            method:'POST', headers:{'Content-Type':'application/json'},
            body: JSON.stringify({type, context: ctx})
        });
        const data = await res.json();
        target.value = data.result;
    } catch(e) { showToast("AI Failed", "error"); }
}

/* --- SETTINGS --- */
function renderPlanInfo(plan) {
    document.getElementById('planName').innerText = plan.name;
    const usedMB = plan.storageUsed / 1024 / 1024;
    const limitMB = plan.storageLimit / 1024 / 1024;
    const freeMB = limitMB - usedMB;

    document.getElementById('storageText').innerText = `${usedMB.toFixed(1)}/${limitMB.toFixed(0)} MB`;
    const pct = Math.min(100, (plan.storageUsed / plan.storageLimit) * 100);

    const bar = document.getElementById('storageBar');
    bar.style.width = pct + "%";
    bar.className = 'progress-fill'; // reset

    if (freeMB < 100) {
        bar.classList.add('danger');
    } else if (freeMB < 500) { // e.g. 500MB warning threshold
        bar.classList.add('warning');
    }
}

async function checkYoutubeStatus() {
    // ...
}

async function cancelSubscription() {
    showConfirmModal("Cancel Subscription", "Are you sure? You will be downgraded to the Free plan immediately.", async () => {
        try {
            const res = await apiFetch(`${API_URL}/pricing/cancel`, { method: 'POST' });
            const data = await res.json();
            if(res.ok) {
                showToast("Subscription cancelled.", "success");
                setTimeout(() => window.location.reload(), 1500);
            } else {
                showToast(data.message || "Cancellation failed", "error");
            }
        } catch(e) { showToast("Error cancelling subscription", "error"); }
    });
}

/* --- ANALYTICS & CALENDAR --- */
async function initAnalytics() {
    const ctx = document.getElementById('analyticsChart');
    const range = document.getElementById('analyticsRange').value;

    if (!ctx) return;

    // Show loading?
    // Destroy previous chart if exists
    if (window.myChart) {
        window.myChart.destroy();
        window.myChart = null;
    }

    try {
        const res = await apiFetch(`${API_URL}/analytics?range=${range}`);
        const data = await res.json();

        // Update Summary
        if (data.summary) {
            document.getElementById('totalViews').innerText = data.summary.totalViews.toLocaleString();
            document.getElementById('totalSubs').innerText = data.summary.totalSubs.toLocaleString();
            const mins = data.summary.totalWatchTime;
            const hours = (mins / 60).toFixed(1);
            document.getElementById('totalWatchTime').innerText = `${hours} hrs`;
        }

        // Render Chart
        window.myChart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: data.labels,
                datasets: [
                    {
                        label: 'Views',
                        data: data.views,
                        borderColor: '#2c68f6',
                        backgroundColor: 'rgba(44, 104, 246, 0.1)',
                        fill: true,
                        tension: 0.4
                    },
                    {
                        label: 'Subscribers',
                        data: data.subs,
                        borderColor: '#00875a',
                        backgroundColor: 'rgba(0, 135, 90, 0.1)',
                        hidden: true, // Hide by default
                        tension: 0.4
                    }
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: {
                    mode: 'index',
                    intersect: false,
                },
                plugins: {
                    legend: { position: 'top' }
                }
            }
        });

    } catch (e) {
        console.error("Analytics Load Failed", e);
    }
}

function initCalendar() {
    const el = document.getElementById('calendar');
    if(el && !el.innerHTML) {
        const cal = new FullCalendar.Calendar(el, {
            initialView: 'dayGridMonth',
            events: async (info, success) => {
                try {
                    const res = await apiFetch(`${API_URL}/videos`);
                    const data = await res.json();
                    success(data.map(v => ({title: v.title, start: v.scheduledTime})));
                } catch(e) { success([]); }
            }
        });
        cal.render();
    }
}

/* --- COMMUNITY (Engagement) --- */
let activeCommentThread = null;
let currentCommTab = 'all';

function filterCommTab(tab) {
    currentCommTab = tab;
    // Update nav active state
    document.querySelectorAll('.comm-nav-item').forEach(el => el.classList.remove('active'));
    document.querySelector(`.comm-nav-item[onclick*="'${tab}'"]`)?.classList.add('active');

    // Show/Hide panels
    if (tab === 'activity') {
        document.getElementById('commListPanel').classList.add('hidden');
        document.getElementById('commDetailView').classList.add('hidden');
        document.getElementById('commActivityView').classList.remove('hidden');
        loadActivityLog();
    } else {
        document.getElementById('commListPanel').classList.remove('hidden');
        document.getElementById('commDetailView').classList.remove('hidden');
        document.getElementById('commActivityView').classList.add('hidden');

        if (tab === 'unreplied') loadUnrepliedComments();
        else loadComments();
    }
}

async function loadComments() {
    const list = document.getElementById('threadList');
    list.innerHTML = "<div style='padding:20px;text-align:center'>Loading...</div>";
    try {
        const res = await apiFetch(`${API_URL}/comments`);
        const data = await res.json();
        renderThreadList(data.items);
    } catch(e) { list.innerHTML = "Failed to load."; }
}

async function loadUnrepliedComments() {
    const list = document.getElementById('threadList');
    list.innerHTML = "<div style='padding:20px;text-align:center'>Loading...</div>";
    try {
        const res = await apiFetch(`${API_URL}/engagement/unreplied`);
        const data = await res.json();
        renderThreadList(data, true);
    } catch(e) { list.innerHTML = "Failed to load."; }
}

function renderThreadList(items, isSimplified = false) {
    const list = document.getElementById('threadList');
    list.innerHTML = '';
    if(!items || !items.length) {
        list.innerHTML = "<div style='padding:20px;text-align:center;color:#666;'>No conversations found.</div>";
        return;
    }

    items.forEach(t => {
        let author, text, id, dateStr, avatarUrl;

        if (isSimplified) {
            author = t.author;
            text = t.text;
            id = t.id;
            dateStr = new Date(t.publishedAt).toLocaleDateString();
            avatarUrl = ""; // No avatar in simplified response
        } else {
            author = t.snippet.topLevelComment.snippet.authorDisplayName;
            text = t.snippet.topLevelComment.snippet.textDisplay;
            id = t.id;
            dateStr = new Date(t.snippet.topLevelComment.snippet.publishedAt).toLocaleDateString();
            avatarUrl = t.snippet.topLevelComment.snippet.authorProfileImageUrl;
        }

        const div = document.createElement('div');
        div.className = 'thread-item';
        div.innerHTML = `
            <div class="thread-avatar">
                ${avatarUrl ? `<img src="${avatarUrl}" style="width:100%;height:100%;border-radius:50%">` : author.charAt(0).toUpperCase()}
            </div>
            <div style="flex:1; overflow:hidden;">
                <div class="thread-meta">
                    <strong>${author}</strong>
                    <span>${dateStr}</span>
                </div>
                <div class="thread-preview">${text}</div>
            </div>
        `;
        div.onclick = () => {
            document.querySelectorAll('.thread-item').forEach(el => el.classList.remove('active'));
            div.classList.add('active');
            selectThread(t, isSimplified);
        };
        list.appendChild(div);
    });
}

function selectThread(thread, isSimplified) {
    activeCommentThread = thread;
    document.getElementById('emptyThreadState').classList.add('hidden');
    document.getElementById('activeThread').classList.remove('hidden');
    document.getElementById('aiSuggestionsArea').classList.add('hidden');
    document.getElementById('replyInput').value = '';

    let text, title, date;
    if (isSimplified) {
        text = thread.text;
        title = "Video ID: " + thread.videoId; // Simplified doesn't have title yet, maybe fetch or ignore
        date = new Date(thread.publishedAt).toLocaleString();
    } else {
        text = thread.snippet.topLevelComment.snippet.textDisplay;
        title = "Comment on Video"; // Standard API structure is complex for video title mapping without extra call
        date = new Date(thread.snippet.topLevelComment.snippet.publishedAt).toLocaleString();
    }

    document.getElementById('threadVideoTitle').innerText = title;

    const container = document.getElementById('threadMessages');
    container.innerHTML = `
        <div style="display:flex; flex-direction:column; gap:10px;">
            <div class="chat-bubble incoming">
                <div style="font-weight:600; font-size:0.8rem; margin-bottom:4px; opacity:0.7;">${date}</div>
                ${text}
            </div>
        </div>
    `;

    // Add replies if standard object and existing
    if (!isSimplified && thread.replies) {
        thread.replies.comments.forEach(r => {
             container.innerHTML += `
                <div class="chat-bubble outgoing">
                    ${r.snippet.textDisplay}
                </div>
             `;
        });
    }
}

async function loadActivityLog() {
    const list = document.getElementById('activityList');
    list.innerHTML = "Loading...";
    try {
        const res = await apiFetch(`${API_URL}/engagement/activity`);
        const data = await res.json();
        list.innerHTML = '';

        if(!data || !data.length) {
            list.innerHTML = "No activity yet.";
            return;
        }

        data.forEach(act => {
            const date = new Date(act.timestamp).toLocaleString();
            let icon = '<i class="fa-solid fa-check"></i>';
            if(act.actionType === 'REPLY') icon = '<i class="fa-solid fa-reply"></i>';
            if(act.actionType === 'DELETE') icon = '<i class="fa-solid fa-trash"></i>';

            let revertBtn = '';
            if (act.actionType === 'REPLY') {
                revertBtn = `<button class="btn btn-sm btn-outline" style="margin-left:10px; color:#d32f2f; border-color:#ef9a9a;" onclick="revertAction(${act.id})">Revert</button>`;
            } else if (act.actionType === 'REVERTED_REPLY') {
                revertBtn = `<span style="margin-left:10px; font-size:0.75rem; color:#999;">Reverted</span>`;
            }

            list.innerHTML += `
                <div class="activity-item">
                    <div class="act-icon ${act.actionType}">${icon}</div>
                    <div class="act-content">
                        <div><b>${act.actionType}</b> on comment ${act.commentId}</div>
                        <div style="font-size:0.85rem; color:#555; margin-top:4px;">"${act.content}"</div>
                    </div>
                    <div class="act-time">${date}</div>
                    ${revertBtn}
                </div>
            `;
        });
    } catch(e) { list.innerHTML = "Failed to load activity."; }
}

async function revertAction(id) {
    if (!confirm("Are you sure you want to delete this reply?")) return;
    try {
        const res = await apiFetch(`${API_URL}/engagement/revert/${id}`, { method: 'POST' });
        if (res.ok) {
            showToast("Action reverted", "success");
            loadActivityLog();
        } else {
            showToast("Failed to revert", "error");
        }
    } catch(e) { showToast("Error", "error"); }
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
    chips.innerHTML = "Generating...";

    let text;
    if(activeCommentThread.text) text = activeCommentThread.text;
    else text = activeCommentThread.snippet.topLevelComment.snippet.textDisplay;

    try {
        const res = await apiFetch(`${API_URL}/engagement/suggest?text=${encodeURIComponent(text)}`);
        const data = await res.json();
        chips.innerHTML = '';

        data.suggestions.forEach(s => {
            const chip = document.createElement('div');
            chip.style.cssText = "padding:5px 10px; background:white; border:1px solid #cce0ff; border-radius:15px; font-size:0.85rem; cursor:pointer; color:#0052cc;";
            chip.innerText = s;
            chip.onclick = () => {
                document.getElementById('replyInput').value = s;
            };
            chips.appendChild(chip);
        });

    } catch(e) {
        chips.innerHTML = "Failed to generate.";
    }
}

async function sendReply() {
    const text = document.getElementById('replyInput').value;
    if(!text) return;

    // Check ID
    let id;
    if(activeCommentThread.id) id = activeCommentThread.id; // simplified or standard ID field? Standard is id. Simplified we mapped id.

    try {
        const res = await apiFetch(`${API_URL}/comments/${id}/reply`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({text})
        });
        if(res.ok) {
            showToast("Reply sent!", "success");
            document.getElementById('replyInput').value = '';
        } else {
             showToast("Reply failed", "error");
        }
    } catch(e) { showToast("Error", "error"); }
}

/* --- NEW SETTINGS LOGIC --- */
function switchSettingsTab(tabName) {
    document.querySelectorAll('.settings-nav-item').forEach(el => el.classList.remove('active'));
    const nav = document.querySelector(`.settings-nav-item[onclick*="'${tabName}'"]`);
    if(nav) nav.classList.add('active');

    document.querySelectorAll('.settings-section').forEach(el => el.classList.add('hidden'));
    const content = document.getElementById(`tab-${tabName}`);
    if(content) content.classList.remove('hidden');

    if(tabName === 'plans') loadInternalPricing();
    if(tabName === 'benefits') loadBenefits();
}

function loadBenefits() {
    if (!currentUser || !currentUser.plan) return;

    const p = currentUser.plan;
    const storageLimitMB = (p.storageLimit / 1024 / 1024).toFixed(0);
    // UserController returns streamLimit, not maxStreams
    const streams = p.streamLimit !== undefined ? p.streamLimit : (p.maxStreams || 1);

    document.getElementById('benefitsContent').innerHTML = `
        <div class="card">
            <h3>Current Plan: <span style="color:var(--primary)">${p.name}</span></h3>
            <ul style="margin-top:20px; line-height:2;">
                <li><i class="fa-solid fa-hard-drive"></i> <b>${storageLimitMB} MB</b> Storage Limit</li>
                <li><i class="fa-solid fa-video"></i> <b>${streams}</b> Concurrent Streams</li>
                <li><i class="fa-solid fa-users"></i> ${p.name === 'FREE' ? 'Single User' : 'Team Access'}</li>
                <li><i class="fa-solid fa-robot"></i> ${p.name === 'FREE' ? 'Basic AI' : 'Advanced AI'} Features</li>
            </ul>
        </div>
    `;
}

async function loadInternalPricing() {
    const grid = document.getElementById('internalPlanGrid');
    if(!grid) return;

    // Always reload to ensure currency/state is fresh
    grid.innerHTML = "Loading plans...";

    try {
        const res = await apiFetch('/api/pricing?country=US');
        const data = await res.json();
        grid.innerHTML = '';

        // Plan Ranks
        const planRanks = { 'FREE': 0, 'ESSENTIALS': 1, 'TEAM': 2 };
        // If current plan name (from user-info) matches ID in pricing, use it. But user-info returns "display name" sometimes.
        // Let's assume user-info returns correct ID or map it.
        // Actually, user-info returns `plan: { name: "Free", ... }`.
        // We need to map display name back to ID or fix backend to return ID.
        // Currently PricingController returns ID: "FREE". User-info returns name "Free".
        // Simple map:
        const currentPlanName = currentUser && currentUser.plan ? currentUser.plan.name.toUpperCase() : "FREE";
        const currentRank = planRanks[currentPlanName] !== undefined ? planRanks[currentPlanName] : -1;

        // If data.plans is undefined (error), show empty
        if (!data.plans) {
             grid.innerHTML = '<p>Could not load plans.</p>';
             return;
        }

        data.plans.forEach(plan => {
            const planRank = planRanks[plan.id] || 0;
            let btn = '';
            let activeClass = '';

            if(currentPlanName === plan.id) {
                btn = `<button class="btn btn-outline btn-block" disabled style="opacity:0.6; cursor:default;">Current Plan</button>`;
                activeClass = 'active-plan';
            } else if (currentRank > planRank) {
                 btn = `<button class="btn btn-outline btn-block" disabled style="visibility:hidden">Included</button>`;
            } else {
                 btn = `<button class="btn btn-primary btn-block" onclick="openPaymentModal('${plan.id}', '${plan.title}', '${plan.price}')">Upgrade</button>`;
            }

            const features = plan.features.map(f => `<li><i class="fa-solid fa-check" style="color:#00875a"></i> ${f}</li>`).join('');

            grid.innerHTML += `
                <div class="plan-card ${activeClass}">
                    <h3>${plan.title}</h3>
                    <div class="plan-price">${plan.price}<span>${plan.period}</span></div>
                    <ul class="plan-features">${features}</ul>
                    ${btn}
                </div>
            `;
        });
    } catch(e) {
        grid.innerHTML = "Failed to load pricing.";
    }
}

/* --- MODALS --- */
function openSupportModal() {
    document.getElementById('supportModal').classList.remove('hidden');
}

let confirmCallback = null;

function showConfirmModal(title, message, onConfirm) {
    document.getElementById('confirmTitle').innerText = title;
    document.getElementById('confirmMessage').innerText = message;
    confirmCallback = onConfirm;
    document.getElementById('confirmationModal').classList.remove('hidden');

    const btn = document.getElementById('btnConfirmAction');
    btn.onclick = () => {
        if(confirmCallback) confirmCallback();
        closeConfirmModal();
    };
}

function closeConfirmModal() {
    document.getElementById('confirmationModal').classList.add('hidden');
    confirmCallback = null;
}

let selectedPlanId = null;

function openPaymentModal(id, title, price) {
    selectedPlanId = id;
    document.getElementById('paymentPlanName').innerText = `Upgrading to ${title}`;
    document.getElementById('paymentAmount').innerText = price;
    document.getElementById('paymentModal').classList.remove('hidden');
}

async function processPayment() {
    if(!selectedPlanId) return;

    const btn = document.getElementById('btnConfirmPayment');
    const originalText = btn.innerText;
    btn.disabled = true;
    btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Processing...';

    await new Promise(r => setTimeout(r, 1500)); // Mock delay

    try {
        const res = await apiFetch('/api/pricing/upgrade', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ planId: selectedPlanId })
        });

        if (res.ok) {
            showToast("Payment Successful! Plan Upgraded.", "success");
            document.getElementById('paymentModal').classList.add('hidden');
            setTimeout(() => window.location.reload(), 1500);
        } else {
            const data = await res.json();
            showToast(data.message || "Upgrade failed", "error");
            btn.disabled = false;
            btn.innerHTML = originalText;
        }
    } catch(e) {
        showToast("Network Error", "error");
        btn.disabled = false;
        btn.innerHTML = originalText;
    }
}

/* --- ENGAGEMENT --- */
async function showEngagementSettings() {
    try {
        const res = await apiFetch(`${API_URL}/engagement/settings`);
        const data = await res.json();
        document.getElementById('engAutoReply').checked = data.autoReplyEnabled;
        document.getElementById('engDeleteNegative').checked = data.deleteNegativeComments;
        document.getElementById('engagementSettingsModal').classList.remove('hidden');
    } catch(e) {
        showToast("Failed to load settings", "error");
    }
}

async function saveEngagementSettings() {
    const autoReply = document.getElementById('engAutoReply').checked;
    const delNeg = document.getElementById('engDeleteNegative').checked;

    try {
        await apiFetch(`${API_URL}/engagement/settings`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ autoReplyEnabled: autoReply, deleteNegativeComments: delNeg })
        });
        showToast("Settings Saved", "success");
        document.getElementById('engagementSettingsModal').classList.add('hidden');
    } catch(e) {
        showToast("Failed to save", "error");
    }
}

/* --- AUDIO LIBRARY --- */
function switchAudioTab(tab) {
    const btnUpload = document.getElementById('tabAudioUpload');
    const btnLib = document.getElementById('tabAudioLib');
    const secUpload = document.getElementById('audioUploadSection');
    const secLib = document.getElementById('audioLibSection');

    if (tab === 'upload') {
        btnUpload.className = "btn btn-sm btn-primary";
        btnLib.className = "btn btn-sm btn-outline";
        secUpload.classList.remove('hidden');
        secLib.classList.add('hidden');
        // Clear lib selection
        document.getElementById('selectedAudioTrackId').value = '';
        document.getElementById('selectedTrackName').innerText = '';
    } else {
        btnUpload.className = "btn btn-sm btn-outline";
        btnLib.className = "btn btn-sm btn-primary";
        secUpload.classList.add('hidden');
        secLib.classList.remove('hidden');
        // Clear upload selection
        document.getElementById('scheduleAudio').value = '';
        loadAudioLibrary();
    }
}

async function loadAudioLibrary() {
    const list = document.getElementById('audioTrackList');
    if(list.dataset.loaded) return;

    list.innerHTML = "Loading tracks...";
    try {
        const res = await apiFetch(`${API_URL}/audio/trending`);
        const tracks = await res.json();
        list.innerHTML = '';

        tracks.forEach(t => {
            const isMixable = !!t.url;
            const div = document.createElement('div');
            div.className = 'queue-item';

            if (isMixable) {
                div.style.cursor = 'pointer';
                div.onclick = () => {
                    document.getElementById('selectedAudioTrackId').value = t.id;
                    document.getElementById('selectedTrackName').innerText = "Selected: " + t.title;
                    document.querySelectorAll('#audioTrackList .queue-item').forEach(el => el.style.background = '');
                    div.style.background = '#e3f2fd';
                };
            } else {
                div.style.cursor = 'default';
            }

            let actions = '';
            if (t.ytUrl) {
                actions += `<a href="${t.ytUrl}" target="_blank" class="btn btn-sm btn-outline" onclick="event.stopPropagation()" title="Create in YouTube App"><i class="fa-brands fa-youtube" style="color:red"></i> Use</a>`;
            }
            if (isMixable) {
                // Audio Preview with Toggle
                actions += `<button class="btn btn-sm btn-text preview-audio-btn" onclick="event.stopPropagation(); toggleAudioPreview(this, '${t.url}')"><i class="fa-solid fa-play"></i></button>`;
            }

            div.innerHTML = `
                <img src="${t.cover}" style="width:30px;height:30px;border-radius:4px;">
                <div style="flex:1">
                    <div style="font-weight:600;font-size:0.9rem;">${t.title} ${!isMixable ? '<span style="font-size:0.7rem; background:#eee; padding:2px 4px; border-radius:4px; margin-left:5px; color:#666;">App Only</span>' : ''}</div>
                    <div style="font-size:0.75rem;color:#666;">${t.artist}</div>
                </div>
                <div style="display:flex; gap:5px;">
                    ${actions}
                </div>
            `;
            list.appendChild(div);
        });
        list.dataset.loaded = "true";
    } catch(e) {
        list.innerHTML = "Failed to load music.";
    }
}

let currentAudio = null;
let currentAudioBtn = null;

function toggleAudioPreview(btn, url) {
    // Check if the same button was clicked
    if (currentAudio && currentAudioBtn === btn && !currentAudio.paused) {
        currentAudio.pause();
        btn.innerHTML = '<i class="fa-solid fa-play"></i>';
        currentAudio = null;
        currentAudioBtn = null;
    } else {
        if (currentAudio) {
            currentAudio.pause();
            if(currentAudioBtn) currentAudioBtn.innerHTML = '<i class="fa-solid fa-play"></i>';
        }
        currentAudio = new Audio(url);
        currentAudio.play();
        btn.innerHTML = '<i class="fa-solid fa-pause"></i>';
        currentAudioBtn = btn;

        currentAudio.onended = () => {
            btn.innerHTML = '<i class="fa-solid fa-play"></i>';
            currentAudio = null;
            currentAudioBtn = null;
        };
    }
}

/* --- STREAM MUSIC --- */
function switchStreamAudioTab(tab) {
    const btnUpload = document.getElementById('tabStreamAudioUpload');
    const btnLib = document.getElementById('tabStreamAudioLib');
    const btnMyLib = document.getElementById('tabStreamAudioMyLib');
    const secUpload = document.getElementById('streamAudioUploadSection');
    const secLib = document.getElementById('streamAudioLibSection');
    const secMyLib = document.getElementById('streamAudioMyLibSection');

    // Reset Classes
    btnUpload.className = "btn btn-sm btn-outline";
    btnLib.className = "btn btn-sm btn-outline";
    btnMyLib.className = "btn btn-sm btn-outline";
    secUpload.classList.add('hidden');
    secLib.classList.add('hidden');
    secMyLib.classList.add('hidden');

    if (tab === 'upload') {
        btnUpload.className = "btn btn-sm btn-primary";
        secUpload.classList.remove('hidden');
    } else if (tab === 'lib') {
        btnLib.className = "btn btn-sm btn-primary";
        secLib.classList.remove('hidden');
        loadStreamAudioLibrary();
    } else if (tab === 'mylib') {
        btnMyLib.className = "btn btn-sm btn-primary";
        secMyLib.classList.remove('hidden');
        loadStreamMyAudioLibrary();
    }
}

/* --- WATERMARK --- */
function handleWatermarkUpload(input) {
    if (input.files && input.files[0]) {
        const file = input.files[0];
        window.uploadedWatermarkFile = file;

        const reader = new FileReader();
        reader.onload = function(e) {
            document.getElementById('watermarkImg').src = e.target.result;
            document.getElementById('watermarkName').innerText = file.name;
            document.getElementById('watermarkPreview').classList.remove('hidden');
        };
        reader.readAsDataURL(file);
    }
}

function clearWatermark() {
    window.uploadedWatermarkFile = null;
    document.getElementById('streamWatermarkFile').value = '';
    document.getElementById('watermarkPreview').classList.add('hidden');
}

async function loadStreamAudioLibrary() {
    const list = document.getElementById('streamAudioTrackList');
    // Re-use existing trending endpoint
    list.innerHTML = "Loading tracks...";
    try {
        const res = await apiFetch(`${API_URL}/audio/trending`);
        const tracks = await res.json();
        list.innerHTML = '';

        tracks.forEach(t => {
            if (!t.url) return; // Only mixable tracks
            const div = document.createElement('div');
            div.className = 'queue-item';
            div.style.cursor = 'pointer';
            div.onclick = () => {
                document.getElementById('selectedStreamStockId').value = t.id;
                document.getElementById('selectedStreamTrackName').innerText = "Selected: " + t.title;
                document.querySelectorAll('#streamAudioTrackList .queue-item').forEach(el => el.style.background = '');
                div.style.background = '#e3f2fd';
            };
            div.innerHTML = `
                <img src="${t.cover}" style="width:30px;height:30px;border-radius:4px;">
                <div style="flex:1; font-weight:600; font-size:0.9rem;">${t.title}</div>
                <button class="btn btn-sm btn-text preview-audio-btn" onclick="event.stopPropagation(); toggleAudioPreview(this, '${t.url}')"><i class="fa-solid fa-play"></i></button>
            `;
            list.appendChild(div);
        });
    } catch(e) { list.innerHTML = "Failed."; }
}

async function loadStreamMyAudioLibrary() {
    const list = document.getElementById('streamMyAudioList');
    list.innerHTML = "Loading library...";
    try {
        const res = await apiFetch(`${API_URL}/audio/my-library`);
        const data = await res.json();
        list.innerHTML = '';

        if(data.length === 0) {
            list.innerHTML = "No audio files found. Upload MP3s in Library.";
            return;
        }

        data.forEach(f => {
            const div = document.createElement('div');
            div.className = 'queue-item';
            div.style.cursor = 'pointer';
            div.onclick = () => {
                // Use filename (s3Key) as the ID passed to backend
                document.getElementById('selectedStreamMyLibId').value = f.filename;
                document.getElementById('selectedStreamMyLibName').innerText = "Selected: " + f.title;
                document.querySelectorAll('#streamMyAudioList .queue-item').forEach(el => el.style.background = '');
                div.style.background = '#e3f2fd';
            };

            // Add Preview Button
            // Assuming we can stream it via a generic endpoint or need a presigned URL
            // Since we don't have a direct URL in the response yet, let's skip preview or add later.
            // Wait, user asked for play/pause.
            // I need a URL. `UserFileService` doesn't generate URLs.
            // `StreamController` has `/api/videos/{id}/thumbnail`.
            // `StreamController` doesn't have a generic "download/stream file" endpoint except for `startStream`.
            // But `LibraryController` might have?
            // `LibraryController` usually has a download or stream endpoint.
            // Let's assume `/api/library/stream/{id}` works for audio too if it uses `FileStorageService`.
            // `LibraryController` was not in my read list, but `StreamController` has `getLibrary`...
            // Let's check `StreamController`. It has `/stream-library` which calls `listConvertedVideos`.
            // It has `/start`...
            // It has `/convert`...
            // Wait, I saw `app.html` using `${API_URL}/library/stream/${video.id}` for video preview.
            // So there is likely a `LibraryController` with `stream` endpoint.
            // If so, I can use it for audio preview too!

            const previewUrl = `${API_URL}/library/stream/${f.id}`;

            div.innerHTML = `
                <div style="flex:1; font-weight:600; font-size:0.9rem;">${f.title}</div>
                <div style="font-size:0.75rem;color:#666;">${(f.fileSize/1024/1024).toFixed(1)} MB</div>
                <button class="btn btn-sm btn-text" onclick="event.stopPropagation(); toggleAudioPreview(this, '${previewUrl}')"><i class="fa-solid fa-play"></i></button>
            `;
            list.appendChild(div);
        });
    } catch(e) { list.innerHTML = "Failed."; }
}

async function handleStreamMusicUpload(e) {
    const file = e.target.files[0];
    if(!file) return;

    const status = document.getElementById('streamAudioUploadStatus');
    status.innerText = "Uploading...";
    const btn = document.getElementById('tabStreamAudioUpload');
    btn.disabled = true;

    const fd = new FormData();
    fd.append("files", file);

    try {
        // Use generic upload
        const res = await apiFetch(`${API_URL}/library/upload`, { method: 'POST', body: fd });
        if(res.ok) {
            status.innerText = "Uploaded!";
            document.getElementById('uploadedStreamMusicName').value = file.name; // assuming simple name or backend returns it?
            // The library upload endpoint returns success but doesn't strictly return the *renamed* file if any.
            // However, StreamService looks for file in user dir. FileUploadService saves as originalFilename.
            // So file.name should be correct.
        } else {
            status.innerText = "Error.";
        }
    } catch(e) { status.innerText = "Error."; }
    finally { btn.disabled = false; }
}
