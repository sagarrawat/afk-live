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

    // Close mobile menu if open
    document.querySelector('.sub-sidebar')?.classList.remove('open');
}

function toggleMobileMenu() {
    const sb = document.getElementById('subSidebar');
    sb.classList.toggle('open');
}

/* --- USER & CHANNELS --- */
async function fetchUserInfo() {
    try {
        const res = await fetch('/api/user-info');
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
        const res = await fetch(`${API_URL}/channels`);
        userChannels = await res.json();

        renderChannelList(userChannels);
    } catch(e) {}
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

function addMockChannel() {
    document.getElementById('newChannelName').value = '';
    document.getElementById('addChannelModal').classList.remove('hidden');
    document.getElementById('newChannelName').focus();
}

async function submitAddChannel() {
    const name = document.getElementById('newChannelName').value;
    if(!name) {
        showToast("Please enter a channel name", "error");
        return;
    }

    // Simulate API call delay for realism
    const btn = document.querySelector('#addChannelModal .btn-primary');
    const originalText = btn.innerText;
    btn.innerText = "Connecting...";
    btn.disabled = true;

    try {
        const res = await fetch(`${API_URL}/channels`, {
             method: 'POST',
             headers: {'Content-Type': 'application/json'},
             body: JSON.stringify({name: name})
        });
        if(res.ok) {
            showToast("Channel Connected Successfully", "success");
            document.getElementById('addChannelModal').classList.add('hidden');
            loadUserChannels();
        } else {
             showToast("Connection failed", "error");
        }
    } catch(e) {
        showToast("Error adding channel", "error");
    } finally {
        btn.innerText = originalText;
        btn.disabled = false;
    }
}

/* --- PUBLISHING --- */
function showScheduleModal() {
    document.getElementById('scheduleModal').classList.remove('hidden');
    // Load categories if empty
    const cat = document.getElementById('scheduleCategory');
    if(cat.options.length === 0) {
        fetch(`${API_URL}/youtube/categories`)
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

    // Auto title
    const titleInput = document.getElementById('scheduleTitle');
    if(!titleInput.value) titleInput.value = file.name.replace(/\.[^/.]+$/, "");
}

async function submitSchedule() {
    const file = document.getElementById('scheduleFile').files[0];
    const title = document.getElementById('scheduleTitle').value;
    const time = document.getElementById('scheduleTime').value;

    if(!file || !title || !time) return showToast("Please fill Title, Time and select a Video.", "error");

    const btn = document.getElementById('btnSchedule');
    btn.disabled = true;
    btn.innerText = "Uploading...";

    const formData = new FormData();
    formData.append("file", file);
    formData.append("title", title);
    formData.append("scheduledTime", time);
    formData.append("description", document.getElementById('scheduleDescription').value);
    formData.append("privacyStatus", document.getElementById('schedulePrivacy').value);
    formData.append("categoryId", document.getElementById('scheduleCategory').value);
    formData.append("tags", document.getElementById('scheduleTags').value);
    if(selectedChannelId) formData.append("socialChannelId", selectedChannelId);

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
        const res = await fetch(`${API_URL}/videos`);
        const videos = await res.json();

        list.innerHTML = '';
        if(videos.length === 0) {
            list.innerHTML = `<div class="empty-state">No posts in queue.</div>`;
            return;
        }

        videos.forEach(v => {
            const statusClass = v.status === 'UPLOADED' ? 'color:#00875a' : (v.status === 'FAILED' ? 'color:#e02424' : 'color:#6b778c');
            list.innerHTML += `
                <div class="queue-item">
                    <div class="queue-thumb"><i class="fa-solid fa-film"></i></div>
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

async function openLibraryModalForStream() {
    document.getElementById('streamLibraryModal').classList.remove('hidden');
    const list = document.getElementById('streamLibraryList');
    list.innerHTML = "Loading...";

    try {
        const res = await fetch(`${API_URL}/library`);
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
                 selectedStreamVideo = v;
                 document.getElementById('selectedVideoPreview').classList.remove('hidden');
                 document.getElementById('videoSelectionButtons').classList.add('hidden');
                 document.getElementById('selectedVideoName').innerText = v.title;
                 document.getElementById('streamLibraryModal').classList.add('hidden');
             };
             list.appendChild(div);
        });
    } catch(e) {}
}

function clearSelectedVideo() {
    selectedStreamVideo = null;
    document.getElementById('selectedVideoPreview').classList.add('hidden');
    document.getElementById('videoSelectionButtons').classList.remove('hidden');
}

async function submitJob() {
    const key = document.getElementById('streamKey').value;
    if(!selectedStreamVideo || !key) return showToast("Select video and enter key", "error");

    const btn = document.getElementById('btnGoLive');
    btn.disabled = true;

    const fd = new FormData();
    fd.append("streamKey", key);
    fd.append("videoKey", selectedStreamVideo.s3Key);

    try {
        const res = await fetch(`${API_URL}/start`, {method:'POST', body:fd});
        const data = await res.json();
        if(data.success) {
            showToast("Stream Started", "success");
            setLiveState(true);
        } else {
            showToast(data.message, "error");
        }
    } catch(e) { showToast("Failed to start", "error"); }
    finally { btn.disabled = false; }
}

async function stopStream() {
    try {
        await fetch(`${API_URL}/stop`, {method:'POST'});
        setLiveState(false);
        showToast("Stream Stopped", "info");
    } catch(e) {}
}

function setLiveState(isLive) {
    const badge = document.getElementById('liveBadge');
    const btnGo = document.getElementById('btnGoLive');
    const btnStop = document.getElementById('btnStop');

    if(isLive) {
        badge.classList.remove('hidden');
        btnGo.classList.add('hidden');
        btnStop.classList.remove('hidden');
        log("Stream is LIVE");
    } else {
        badge.classList.add('hidden');
        btnGo.classList.remove('hidden');
        btnStop.classList.add('hidden');
        log("Stream Offline");
    }
}

function log(msg) {
    const t = document.getElementById('console');
    if(t) t.innerHTML += `<div>[${new Date().toLocaleTimeString()}] ${msg}</div>`;
}

async function checkInitialStatus() {
    try {
        const res = await fetch(`${API_URL}/status`);
        const data = await res.json();
        if(data.success && data.data.live) setLiveState(true);
    } catch(e){}
}

function loadGlobalSettings() {
    const savedKey = localStorage.getItem('afk_stream_key');
    if(savedKey && document.getElementById('streamKey')) {
        document.getElementById('streamKey').value = savedKey;
    }
}

/* --- LIBRARY --- */
async function loadLibraryVideos() {
    const list = document.getElementById('libraryList');
    try {
        const res = await fetch(`${API_URL}/library`);
        const data = await res.json();
        list.innerHTML = '';
        data.data.forEach(v => {
            list.innerHTML += `
                <div class="queue-item">
                     <div class="queue-thumb"><i class="fa-solid fa-file-video"></i></div>
                     <div>${v.title}</div>
                </div>
            `;
        });
    } catch(e){}
}

async function handleBulkUpload(e) {
    const files = e.target.files;
    if(!files.length) return;
    const fd = new FormData();
    for(let f of files) fd.append("files", f);

    showToast("Uploading library...", "info");
    try {
        const res = await fetch(`${API_URL}/library/upload`, {method:'POST', body:fd});
        if(res.ok) {
            showToast("Uploaded!", "success");
            loadLibraryVideos();
        }
    } catch(e){ showToast("Failed", "error"); }
}

async function submitAutoSchedule() {
    // ... similar to previous impl
    showToast("Auto-scheduler initiated", "success");
    document.getElementById('autoScheduleModal').classList.add('hidden');
}

/* --- AI --- */
async function aiGenerate(type) {
    const ctx = document.getElementById('scheduleTitle').value || "Video";
    const target = type === 'description' ? document.getElementById('scheduleDescription') : null;
    if(!target) return;

    target.placeholder = "AI is writing...";
    try {
        const res = await fetch(`${API_URL}/ai/generate`, {
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
    const used = (plan.storageUsed / 1024 / 1024).toFixed(1);
    const limit = (plan.storageLimit / 1024 / 1024).toFixed(0);
    document.getElementById('storageText').innerText = `${used}/${limit} MB`;
    const pct = Math.min(100, (plan.storageUsed / plan.storageLimit) * 100);
    document.getElementById('storageBar').style.width = pct + "%";
}

async function checkYoutubeStatus() {
    // ...
}

/* --- ANALYTICS & CALENDAR --- */
function initAnalytics() {
    // Chart.js init
    const ctx = document.getElementById('analyticsChart');
    if(ctx && !window.myChart) {
        window.myChart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: ['Mon','Tue','Wed','Thu','Fri','Sat','Sun'],
                datasets: [{
                    label: 'Views',
                    data: [12, 19, 3, 5, 2, 3, 15],
                    borderColor: '#2c68f6',
                    tension: 0.4
                }]
            },
            options: { responsive: true, maintainAspectRatio: false }
        });
    }
}

function initCalendar() {
    const el = document.getElementById('calendar');
    if(el && !el.innerHTML) {
        const cal = new FullCalendar.Calendar(el, {
            initialView: 'dayGridMonth',
            events: async (info, success) => {
                const res = await fetch(`${API_URL}/videos`);
                const data = await res.json();
                success(data.map(v => ({title: v.title, start: v.scheduledTime})));
            }
        });
        cal.render();
    }
}

/* --- COMMUNITY --- */
async function loadComments() {
    const list = document.getElementById('threadList');
    list.innerHTML = "Loading...";
    try {
        const res = await fetch(`${API_URL}/comments`);
        const data = await res.json();
        list.innerHTML = '';
        if(!data.items || !data.items.length) {
            list.innerHTML = "<div style='padding:20px;text-align:center'>No conversations.</div>";
            return;
        }
        // ... render threads
        data.items.forEach(t => {
            const div = document.createElement('div');
            div.className = 'queue-item';
            div.style.cursor = 'pointer';
            div.innerHTML = `
                <img src="${t.snippet.topLevelComment.snippet.authorProfileImageUrl}" style="width:32px;height:32px;border-radius:50%">
                <div>${t.snippet.topLevelComment.snippet.textDisplay.substring(0,50)}...</div>
            `;
            list.appendChild(div);
        });
    } catch(e) { list.innerHTML = "Failed to load."; }
}
