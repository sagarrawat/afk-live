const API_URL = "/api";
let uploadedFileName = "";
let currentUser = null;
let logInterval = null;

document.addEventListener("DOMContentLoaded", async () => {
    await fetchUserInfo(); // Check login
    
    // Header User Profile
    const userHeader = document.getElementById("userHeader");
    if (userHeader) {
        userHeader.addEventListener("click", () => {
            if (!currentUser) showLoginModal();
        });
    }

    // Initialize Views
    loadGlobalSettings();
    loadLibrary(); // For streaming view
    loadScheduledQueue(); // For publish view
    checkYoutubeStatus();

    // Event Listeners: Composer
    const dropZone = document.getElementById("dropZone");
    if (dropZone) {
        dropZone.addEventListener("click", () => document.getElementById("scheduleFile").click());
        // Simple drag and drop could be added here
    }
    const scheduleFile = document.getElementById("scheduleFile");
    if (scheduleFile) {
        scheduleFile.addEventListener("change", handleScheduleFileSelect);
    }

    // Event Listeners: Stream Library
    const videoFile = document.getElementById("videoFile");
    if(videoFile) {
        videoFile.addEventListener("change", handleFileUpload);
    }
});

/* --- VIEW SWITCHING --- */
function switchView(viewName) {
    // Update Sidebar
    document.querySelectorAll('.menu-item').forEach(el => el.classList.remove('active'));
    document.querySelector(`[data-target="view-${viewName}"]`)?.classList.add('active');

    // Update Main Content
    document.querySelectorAll('.view-section').forEach(el => el.classList.add('hidden'));
    document.getElementById(`view-${viewName}`).classList.remove('hidden');

    // Update Header Title
    const titles = {
        'publish': 'Publishing',
        'stream': 'Live Studio',
        'settings': 'Settings'
    };
    document.getElementById('pageTitle').innerText = titles[viewName] || 'Dashboard';
}

/* --- PUBLISH / SCHEDULE LOGIC --- */

function showScheduleModal() {
    document.getElementById('scheduleModal').classList.remove('hidden');
}

function closeScheduleModal() {
    document.getElementById('scheduleModal').classList.add('hidden');
}

function handleScheduleFileSelect(e) {
    const file = e.target.files[0];
    if (file) {
        const display = document.getElementById("selectedFileDisplay");
        display.innerText = "📄 " + file.name;
        display.classList.remove("hidden");
    }
}

async function submitSchedule() {
    if (!currentUser) { showLoginModal(); return; }

    const fileInput = document.getElementById('scheduleFile');
    const title = document.getElementById('scheduleTitle').value;
    const description = document.getElementById('scheduleDescription').value;
    const tags = document.getElementById('scheduleTags').value;
    const privacy = document.getElementById('schedulePrivacy').value;
    const time = document.getElementById('scheduleTime').value;
    const btn = document.getElementById('btnSchedule');

    if (!fileInput.files[0] || !title || !time) {
        alert("Please fill all required fields (File, Title, Time)");
        return;
    }

    btn.disabled = true;
    btn.innerText = "Uploading & Scheduling...";

    const formData = new FormData();
    formData.append("file", fileInput.files[0]);
    formData.append("title", title);
    formData.append("description", description);
    formData.append("tags", tags);
    formData.append("privacyStatus", privacy);
    formData.append("scheduledTime", time);

    try {
        const res = await fetch(`${API_URL}/videos/schedule`, {
            method: 'POST',
            body: formData
        });
        const data = await res.json();

        if (data.success) {
            closeScheduleModal();
            loadScheduledQueue(); // Refresh list
            alert("Success! Video scheduled.");

            // Reset form
            fileInput.value = '';
            document.getElementById('scheduleTitle').value = '';
            document.getElementById("selectedFileDisplay").classList.add("hidden");
        } else {
            alert("Error: " + data.message);
        }
    } catch (e) {
        alert("Scheduling Failed");
        console.error(e);
    } finally {
        btn.disabled = false;
        btn.innerText = "Schedule Post";
    }
}

async function loadScheduledQueue() {
    try {
        const res = await fetch(`${API_URL}/videos`);
        const videos = await res.json();
        const container = document.getElementById('queueList');

        if (videos.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    <div class="icon-box">📅</div>
                    <h3>Your queue is empty</h3>
                    <p>Schedule your first video to get started.</p>
                </div>`;
            return;
        }

        container.innerHTML = '';
        videos.forEach(v => {
            const item = document.createElement('div');
            item.className = 'queue-item';

            // Status Badge Logic
            let statusColor = '#888';
            if(v.status === 'UPLOADED') statusColor = 'var(--success)';
            if(v.status === 'FAILED') statusColor = 'var(--danger)';
            if(v.status === 'PROCESSING') statusColor = 'var(--primary)';

            item.innerHTML = `
                <div class="queue-thumb">
                    <i class="fa-solid fa-film fa-lg"></i>
                </div>
                <div class="queue-details">
                    <h4>${v.title}</h4>
                    <div class="queue-meta">
                        <span><i class="fa-regular fa-clock"></i> ${new Date(v.scheduledTime).toLocaleString()}</span>
                        <span style="color: ${statusColor}; font-weight: 600;">${v.status}</span>
                        <span><i class="fa-solid fa-lock"></i> ${v.privacyStatus}</span>
                    </div>
                </div>
            `;
            container.appendChild(item);
        });

    } catch (e) {
        console.error("Failed to load queue", e);
    }
}


/* --- STREAMING LOGIC --- */

// (Adapted from previous version)
async function submitJob(fileNameFromLibrary) {
    const targetFile = fileNameFromLibrary || uploadedFileName;
    const key = document.getElementById("streamKey").value;
    const btn = document.getElementById("btnGoLive");
    
    if (!targetFile) return alert("⚠️ Select a video from the library.");
    if (!key) return alert("⚠️ Please enter a Stream Key.");

    btn.disabled = true;
    btn.innerHTML = `<i class="fa-solid fa-spinner fa-spin"></i> Starting...`;

    const formData = new FormData();
    formData.append("streamKey", key);
    formData.append("fileName", targetFile);

    try {
        const res = await fetch(`${API_URL}/start`, { method: "POST", body: formData });
        const data = await res.json();
        
        if (data.success) {
            setLiveState(true);
            log("Stream Started: " + targetFile);
        } else {
            alert(data.message);
        }
    } catch (err) {
        alert("Failed to start stream");
        log("Error starting stream");
    } finally {
        if(!document.getElementById("liveIndicator").classList.contains("hidden")) {
             // If success, keep disabled/change text
        } else {
             btn.disabled = false;
             btn.innerHTML = `<i class="fa-solid fa-satellite-dish"></i> Go Live`;
        }
    }
}

async function stopStream() {
    try {
        await fetch(`${API_URL}/stop`, { method: "POST" });
        setLiveState(false);
        log("Stream Stopped");
    } catch (err) {
        console.error(err);
    }
}

function setLiveState(isLive) {
    const indicator = document.getElementById("liveIndicator");
    const placeholder = document.querySelector(".preview-placeholder");
    const btnGo = document.getElementById("btnGoLive");
    const btnStop = document.getElementById("btnStop");

    if (isLive) {
        indicator.classList.remove("hidden");
        placeholder.classList.add("hidden");
        btnGo.classList.add("hidden");
        btnStop.classList.remove("hidden");
    } else {
        indicator.classList.add("hidden");
        placeholder.classList.remove("hidden");
        btnGo.classList.remove("hidden");
        btnGo.disabled = false;
        btnGo.innerHTML = `<i class="fa-solid fa-satellite-dish"></i> Go Live`;
        btnStop.classList.add("hidden");
    }
}

/* --- LIBRARY & UPLOAD LOGIC --- */

async function handleFileUpload(e) {
    const file = e.target.files[0];
    if (!file) return;

    log(`Uploading: ${file.name}...`);
    
    const formData = new FormData();
    formData.append("file", file);

    try {
        const res = await fetch(`${API_URL}/upload`, { method: "POST", body: formData });
        if (res.ok) {
            const data = await res.json();
            // In a real app we might transcode. Here we just add to library.
            // But we need the filename returned by server for the streaming backend.
            uploadedFileName = data.data;
            log("Upload Complete. Processing...");

            // Mock processing delay then refresh library
            setTimeout(() => {
                loadLibrary();
                log("Ready to stream.");
            }, 1000);
        }
    } catch (err) {
        log("Upload Failed");
    }
}

async function loadLibrary() {
    try {
        const res = await fetch('/api/library');
        const files = await res.json();
        const grid = document.getElementById('libraryGrid');
        if(!grid) return;

        grid.innerHTML = '';
        files.forEach(file => {
            const item = document.createElement('div');
            item.className = 'lib-item';
            item.innerText = file.replace('ready_', '').substring(0, 20) + '...';
            item.onclick = () => {
                document.querySelectorAll('.lib-item').forEach(el => el.classList.remove('selected'));
                item.classList.add('selected');
                uploadedFileName = file; // Set as target
                log("Selected: " + file);
            };
            grid.appendChild(item);
        });
    } catch (err) {
        console.error("Library load failed", err);
    }
}

/* --- SHARED / UTIL --- */

function log(msg) {
    const consoleDiv = document.getElementById("console");
    if(consoleDiv) {
        const entry = document.createElement("div");
        entry.className = "log-entry";
        entry.innerText = `[${new Date().toLocaleTimeString()}] ${msg}`;
        consoleDiv.appendChild(entry);
        consoleDiv.scrollTop = consoleDiv.scrollHeight;
    }
}

async function fetchUserInfo() {
    try {
        const res = await fetch('/api/user-info');
        const data = await res.json();
        if (data.email) {
            currentUser = data;
            document.getElementById("userHeader").innerHTML = `
                <img src="${data.picture}" style="width:32px; height:32px; border-radius:50%; vertical-align:middle; margin-right:8px;">
                <span>${data.name}</span>
            `;
            checkInitialStatus();
        } else {
            showLoginModal();
        }
    } catch (e) { console.log("Guest"); }
}

function showLoginModal() {
    document.getElementById("loginModal").classList.remove("hidden");
}

function loadGlobalSettings() {
    const savedKey = localStorage.getItem('afk_stream_key');
    if(savedKey && document.getElementById('streamKey')) {
        document.getElementById('streamKey').value = savedKey;
    }
}

async function checkYoutubeStatus() {
    try {
        const res = await fetch(`${API_URL}/youtube/status`);
        const data = await res.json();
        const el = document.getElementById('ytConnectionStatus');
        if (el) {
            el.innerHTML = data.connected ?
                '<span style="color:var(--success)">Connected</span>' :
                '<span style="color:var(--danger)">Disconnected</span>';
        }
    } catch(e) {}
}

async function checkInitialStatus() {
    try {
        const res = await fetch(`${API_URL}/status`);
        const data = await res.json();
        if (data.success && data.data?.live) {
            setLiveState(true);
            log("Resumed active session.");
        }
    } catch(e) {}
}
