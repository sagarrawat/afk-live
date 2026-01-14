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
    // loadLibrary();
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

    // Event Listeners: Audio
    const musicDropZone = document.getElementById("musicDropZone");
    if (musicDropZone) {
        musicDropZone.addEventListener("click", () => document.getElementById("musicFile").click());
        document.getElementById("musicFile").addEventListener("change", handleMusicUpload);
    }

    const volumeSlider = document.getElementById("volumeSlider");
    if (volumeSlider) {
        volumeSlider.addEventListener("input", (e) => {
            document.getElementById("volValue").innerText = e.target.value + "%";
            document.getElementById("audioPreview").volume = e.target.value / 100;
        });
    }

    // Bulk Upload
    const bulkInput = document.getElementById("bulkUploadInput");
    if(bulkInput) bulkInput.addEventListener("change", handleBulkUpload);

    // Stream Direct Upload
    const streamUpload = document.getElementById("streamUploadInput");
    if(streamUpload) streamUpload.addEventListener("change", handleStreamVideoUpload);
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
        'calendar': 'Calendar',
        'analytics': 'Analytics',
        'library': 'Library',
        'settings': 'Settings'
    };
    document.getElementById('pageTitle').innerText = titles[viewName] || 'Dashboard';

    // Lazy Load
    if (viewName === 'calendar') {
        setTimeout(initCalendar, 100); // Small delay to ensure visibility
    }
    if (viewName === 'analytics') {
        initAnalytics();
    }
    if (viewName === 'library') {
        loadLibraryVideos();
    }
}

/* --- CALENDAR LOGIC --- */
function initCalendar() {
    var calendarEl = document.getElementById('calendar');
    if (!calendarEl) return;

    var calendar = new FullCalendar.Calendar(calendarEl, {
        initialView: 'dayGridMonth',
        headerToolbar: {
            left: 'prev,next today',
            center: 'title',
            right: 'dayGridMonth,timeGridWeek,timeGridDay'
        },
        themeSystem: 'standard',
        events: async function(info, successCallback, failureCallback) {
            try {
                const res = await fetch(`${API_URL}/videos`);
                const videos = await res.json();
                const events = videos.map(v => ({
                    title: v.title,
                    start: v.scheduledTime,
                    color: v.status === 'UPLOADED' ? '#2ba640' : (v.status === 'FAILED' ? '#cc0000' : '#2c68f6')
                }));
                successCallback(events);
            } catch (e) {
                failureCallback(e);
            }
        },
        eventClick: function(info) {
            alert('Video: ' + info.event.title + '\nScheduled: ' + info.event.start.toLocaleString());
        }
    });
    calendar.render();
}

/* --- ANALYTICS LOGIC --- */
async function initAnalytics() {
    const ctx = document.getElementById('analyticsChart');
    if (!ctx) return;

    try {
        const res = await fetch(`${API_URL}/mock/analytics`);
        const data = await res.json();

        new Chart(ctx, {
            type: 'line',
            data: {
                labels: data.labels,
                datasets: [{
                    label: 'Views',
                    data: data.views,
                    borderColor: '#2c68f6',
                    tension: 0.4
                }, {
                    label: 'Clicks',
                    data: data.clicks,
                    borderColor: '#27c93f',
                    tension: 0.4
                }]
            },
            options: {
                responsive: true,
                plugins: {
                    legend: {
                        position: 'top',
                    }
                }
            }
        });
    } catch(e) {
        console.error("Failed to load analytics", e);
    }
}

/* --- LIBRARY LOGIC --- */
async function loadLibraryVideos() {
    try {
        const res = await fetch(`${API_URL}/library`);
        const data = await res.json();
        const container = document.getElementById('libraryList');

        if (data.data.length === 0) {
            container.innerHTML = `<div class="empty-state">No videos in library. Upload some!</div>`;
            return;
        }

        container.innerHTML = '';
        data.data.forEach(v => {
            container.innerHTML += `
                <div class="queue-item">
                    <div class="queue-thumb"><i class="fa-solid fa-file-video"></i></div>
                    <div class="queue-details">
                        <h4>${v.title}</h4>
                        <div class="queue-meta">Status: <span style="color:var(--text-secondary)">${v.status}</span></div>
                    </div>
                </div>`;
        });
    } catch (e) {
        console.error("Failed to load library", e);
    }
}

async function handleBulkUpload(e) {
    const files = e.target.files;
    if (!files.length) return;

    const formData = new FormData();
    for (let i = 0; i < files.length; i++) {
        formData.append("files", files[i]);
    }

    const btn = document.querySelector('button[onclick*="bulkUploadInput"]');
    const originalText = btn.innerHTML;
    btn.disabled = true;
    btn.innerText = "Uploading...";

    try {
        const res = await fetch(`${API_URL}/library/upload`, {
            method: 'POST',
            body: formData
        });
        const data = await res.json();
        if (res.ok) {
            alert(data.message);
            loadLibraryVideos();
        } else {
            alert("Error: " + data.message);
        }
    } catch (err) {
        alert("Upload failed");
    } finally {
        btn.disabled = false;
        btn.innerHTML = originalText;
        e.target.value = '';
    }
}

function showAutoScheduleModal() {
    document.getElementById('autoScheduleModal').classList.remove('hidden');
}

async function submitAutoSchedule() {
    const startDate = document.getElementById('autoStartDate').value;
    const slotsStr = document.getElementById('autoTimeSlots').value;

    if (!startDate || !slotsStr) {
        alert("Please fill all fields");
        return;
    }

    const timeSlots = slotsStr.split(',').map(s => s.trim());

    try {
        const res = await fetch(`${API_URL}/library/auto-schedule`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ startDate, timeSlots })
        });
        const data = await res.json();

        if (res.ok) {
            alert(data.message);
            document.getElementById('autoScheduleModal').classList.add('hidden');
            loadLibraryVideos(); // Should be empty/less now
        } else {
            alert("Error: " + data.message);
        }
    } catch (e) {
        alert("Scheduling failed");
    }
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

    // Progress UI
    const progressContainer = document.getElementById("uploadProgressContainer");
    const progressBar = document.getElementById("uploadProgressBar");
    const progressText = document.getElementById("uploadPercent");
    progressContainer.classList.remove("hidden");

    // Use XHR for progress
    const xhr = new XMLHttpRequest();
    xhr.open("POST", `${API_URL}/videos/schedule`, true);

    xhr.upload.onprogress = function(e) {
        if (e.lengthComputable) {
            const percent = Math.round((e.loaded / e.total) * 100);
            progressBar.style.width = percent + "%";
            progressText.innerText = percent + "%";
        }
    };

    xhr.onload = function() {
        btn.disabled = false;
        btn.innerText = "Schedule Post";
        progressContainer.classList.add("hidden");

        if (xhr.status === 200) {
            const data = JSON.parse(xhr.responseText);
            if (data.success) {
                closeScheduleModal();
                loadScheduledQueue();
                alert("Success! Video scheduled.");

                // Reset
                fileInput.value = '';
                document.getElementById('scheduleTitle').value = '';
                document.getElementById("selectedFileDisplay").classList.add("hidden");
            } else {
                alert("Error: " + data.message);
            }
        } else {
            alert("Upload Failed: " + xhr.statusText);
        }
    };

    xhr.onerror = function() {
        btn.disabled = false;
        btn.innerText = "Schedule Post";
        progressContainer.classList.add("hidden");
        alert("Network Error");
    };

    xhr.send(formData);
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

let selectedStreamVideo = null;

async function openLibraryModalForStream() {
    document.getElementById('streamLibraryModal').classList.remove('hidden');
    const container = document.getElementById('streamLibraryList');
    container.innerHTML = '<p>Loading...</p>';

    try {
        const res = await fetch(`${API_URL}/library`);
        const data = await res.json();

        if (!data.success || data.data.length === 0) {
            container.innerHTML = '<p>No videos found. Upload in "Library" first.</p>';
            return;
        }

        container.innerHTML = '';
        data.data.forEach(v => {
            const item = document.createElement('div');
            item.className = 'queue-item';
            item.style.cursor = 'pointer';
            item.onclick = () => selectVideoForStream(v);

            item.innerHTML = `
                <div class="queue-thumb"><i class="fa-solid fa-film"></i></div>
                <div class="queue-details">
                    <h4>${v.title}</h4>
                    <div class="queue-meta">${new Date(v.createdAt || Date.now()).toLocaleDateString()}</div>
                </div>
            `;
            container.appendChild(item);
        });
    } catch (e) {
        container.innerHTML = '<p>Error loading library</p>';
    }
}

function selectVideoForStream(video) {
    selectedStreamVideo = video;
    document.getElementById('streamLibraryModal').classList.add('hidden');

    // Update UI
    document.getElementById('btnSelectVideo').classList.add('hidden');
    document.getElementById('selectedVideoPreview').classList.remove('hidden');
    document.getElementById('selectedVideoName').innerText = video.title;
}

function clearSelectedVideo() {
    selectedStreamVideo = null;
    document.getElementById('btnSelectVideo').classList.remove('hidden');
    document.getElementById('selectedVideoPreview').classList.add('hidden');
}

async function submitJob() {
    const key = document.getElementById("streamKey").value;
    const btn = document.getElementById("btnGoLive");

    if (!selectedStreamVideo) return alert("⚠️ Please select a video from Step 1.");
    if (!key) return alert("⚠️ Please enter a Stream Key in Step 2.");

    btn.disabled = true;
    btn.innerHTML = `<i class="fa-solid fa-spinner fa-spin"></i> Starting...`;

    const formData = new FormData();
    formData.append("streamKey", key);
    formData.append("videoKey", selectedStreamVideo.s3Key);

    if (window.uploadedMusicName) {
        formData.append("musicName", window.uploadedMusicName);
        const volPercent = document.getElementById("volumeSlider").value;
        formData.append("musicVolume", (volPercent / 100).toFixed(2));
    }

    try {
        const res = await fetch(`${API_URL}/start`, { method: "POST", body: formData });
        const data = await res.json();
        
        if (data.success) {
            setLiveState(true);
            log("Stream Started: " + selectedStreamVideo.title);
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
             btn.innerHTML = `Start Streaming`;
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
    const btnGo = document.getElementById("btnGoLive");
    const btnStop = document.getElementById("btnStop");
    const statusText = document.getElementById("streamStatusText");

    if (isLive) {
        indicator.classList.remove("hidden");
        btnGo.classList.add("hidden");
        btnStop.classList.remove("hidden");
        if(statusText) statusText.innerText = "Live";
        if(statusText) statusText.style.color = "var(--success)";
    } else {
        indicator.classList.add("hidden");
        btnGo.classList.remove("hidden");
        btnGo.disabled = false;
        btnGo.innerHTML = `Start Streaming`;
        btnStop.classList.add("hidden");
        if(statusText) statusText.innerText = "Offline";
        if(statusText) statusText.style.color = "var(--text-secondary)";
    }
}

async function handleStreamVideoUpload(e) {
    const file = e.target.files[0];
    if (!file) return;

    const btn = document.getElementById("btnUploadStreamVideo");
    const originalText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = `<i class="fa-solid fa-spinner fa-spin"></i> ...`;

    const formData = new FormData();
    formData.append("files", file);

    try {
        const res = await fetch(`${API_URL}/library/upload`, { method: "POST", body: formData });
        const data = await res.json();
        if (data.success) {
            // Auto-open library to let user select the new video
            openLibraryModalForStream();
        } else {
            alert("Error: " + data.message);
        }
    } catch (err) {
        alert("Upload Failed");
    } finally {
        btn.disabled = false;
        btn.innerHTML = originalText;
        e.target.value = '';
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
            renderPlanInfo(data);
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

function renderPlanInfo(user) {
    if (!user.plan) return;
    const p = user.plan;

    const nameEl = document.getElementById("planName");
    if(nameEl) nameEl.innerText = p.name;

    const usedMB = (p.storageUsed / 1024 / 1024).toFixed(1);
    const limitMB = (p.storageLimit / 1024 / 1024).toFixed(0);
    const percent = Math.min(100, (p.storageUsed / p.storageLimit) * 100);

    const storageText = document.getElementById("storageText");
    if(storageText) storageText.innerText = `${usedMB} / ${limitMB} MB (${percent.toFixed(1)}%)`;

    const bar = document.getElementById("storageBar");
    if(bar) bar.style.width = percent + "%";

    const streamLimit = document.getElementById("streamLimitText");
    if(streamLimit) streamLimit.innerText = p.streamLimit + " Concurrent Stream(s)";
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
