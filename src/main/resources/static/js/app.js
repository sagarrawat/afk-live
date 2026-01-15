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
        'community': 'Community',
        'settings': 'Settings'
    };
    document.getElementById('pageTitle').innerText = titles[viewName] || 'Dashboard';

    // Lazy Load
    if (viewName === 'calendar') {
        setTimeout(initCalendar, 100); // Small delay to ensure visibility
    }
    if (viewName === 'analytics') {
        setTimeout(initAnalytics, 50);
    }
    if (viewName === 'library') {
        loadLibraryVideos();
    }
    if (viewName === 'community') {
        loadComments();
    }
}

/* --- COMMUNITY LOGIC --- */
let activeThreadId = null;
let currentComments = [];

document.addEventListener("DOMContentLoaded", () => {
    // ... existing init ...
    const searchInput = document.querySelector('.search-bar input');
    if(searchInput) {
        searchInput.addEventListener('input', (e) => filterComments(e.target.value));
    }
});

async function loadComments() {
    const list = document.getElementById('threadList');
    list.innerHTML = '<p style="padding:20px; text-align:center;">Loading...</p>';

    try {
        const res = await fetch(`${API_URL}/comments`);
        const data = await res.json();

        if (!data.items || data.items.length === 0) {
            list.innerHTML = '<p style="padding:20px; text-align:center;">No comments found.</p>';
            return;
        }

        currentComments = data.items;
        renderThreadList(data.items);
    } catch (e) {
        list.innerHTML = '<p style="padding:20px; text-align:center; color:red;">Failed to load.</p>';
        console.error(e);
    }
}

function filterComments(query) {
    if (!query) {
        renderThreadList(currentComments);
        return;
    }
    const lower = query.toLowerCase();
    const filtered = currentComments.filter(t => {
        const snip = t.snippet.topLevelComment.snippet;
        return snip.textDisplay.toLowerCase().includes(lower) ||
               snip.authorDisplayName.toLowerCase().includes(lower);
    });
    renderThreadList(filtered);
}

function renderThreadList(threads) {
    const list = document.getElementById('threadList');
    list.innerHTML = '';

    if(threads.length === 0) {
        list.innerHTML = '<p style="padding:20px; text-align:center; color:#999;">No matches found.</p>';
        return;
    }

    threads.forEach(thread => {
        const top = thread.snippet.topLevelComment.snippet;
        const div = document.createElement('div');
        div.className = 'thread-item';
        if (thread.id === activeThreadId) div.classList.add('active');

        div.onclick = () => selectThread(thread.id);

        div.innerHTML = `
            <img src="${top.authorProfileImageUrl}" class="thread-avatar">
            <div class="thread-info">
                <div class="thread-name">${top.authorDisplayName}</div>
                <div class="thread-preview">${top.textDisplay}</div>
                <div class="thread-meta">${new Date(top.publishedAt).toLocaleDateString()} • on ${thread.snippet.videoId}</div>
            </div>
        `;
        list.appendChild(div);
    });
}

function selectThread(id) {
    activeThreadId = id;
    renderThreadList(currentComments); // Re-render to update active state

    const thread = currentComments.find(t => t.id === id);
    if (!thread) return;

    document.getElementById('emptyThreadState').classList.add('hidden');
    document.getElementById('activeThread').classList.remove('hidden');

    // Header
    // We don't have video title in comment resource unfortunately, only videoId.
    // Ideally we would fetch it, but for now use ID.
    document.getElementById('threadVideoTitle').innerText = "Video ID: " + thread.snippet.videoId;
    document.getElementById('threadVideoLink').href = `https://youtube.com/watch?v=${thread.snippet.videoId}&lc=${thread.id}`;

    const container = document.getElementById('threadMessages');
    container.innerHTML = '';

    // Top Level
    const top = thread.snippet.topLevelComment;
    renderMessage(container, top, false);

    // Replies
    if (thread.replies && thread.replies.comments) {
        thread.replies.comments.forEach(reply => renderMessage(container, reply, true));
    }
}

function renderMessage(container, comment, isReply) {
    const snippet = comment.snippet;
    const div = document.createElement('div');
    div.className = `message-bubble ${isReply ? 'reply' : ''}`;

    div.innerHTML = `
        <img src="${snippet.authorProfileImageUrl}">
        <div class="bubble-content">
            <div class="bubble-header">
                <b>${snippet.authorDisplayName}</b>
                <span>${new Date(snippet.publishedAt).toLocaleString()}</span>
                ${canDelete(comment) ? `<i class="fa-solid fa-trash delete-btn" onclick="deleteComment('${comment.id}')"></i>` : ''}
            </div>
            <div class="bubble-text">${snippet.textDisplay}</div>
        </div>
    `;
    container.appendChild(div);
}

function canDelete(comment) {
    // Crude check: if author is me (connected user).
    // In reality, channel owner can delete any comment.
    // We'll assume we can try to delete any for now, backend will reject if not allowed.
    return true;
}

async function sendReply() {
    if (!activeThreadId) return;
    const input = document.getElementById('replyInput');
    const text = input.value;
    if (!text) return;

    const btn = document.querySelector('.reply-box button');
    btn.disabled = true;

    try {
        const res = await fetch(`${API_URL}/comments/${activeThreadId}/reply`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ text })
        });
        const data = await res.json();

        if (data.success) {
            input.value = '';
            // Refresh comments to show new reply
            loadComments();
            // Note: Optimistic UI update would be better here for "Apple" feel
        } else {
            alert("Error: " + data.message);
        }
    } catch (e) {
        alert("Reply failed");
    } finally {
        btn.disabled = false;
    }
}

async function deleteComment(id) {
    if (!confirm("Are you sure you want to delete this comment?")) return;

    try {
        const res = await fetch(`${API_URL}/comments/${id}`, { method: 'DELETE' });
        const data = await res.json();
        if (data.success) {
            loadComments();
            // If we deleted the top level, close the thread view
            if (id === activeThreadId) {
                document.getElementById('emptyThreadState').classList.remove('hidden');
                document.getElementById('activeThread').classList.add('hidden');
                activeThreadId = null;
            }
        } else {
            alert("Error: " + data.message);
        }
    } catch(e) {
        alert("Delete failed");
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
let analyticsChart = null;

async function initAnalytics() {
    const ctx = document.getElementById('analyticsChart');
    if (!ctx) return;

    const range = document.getElementById('analyticsRange').value;
    const endDate = new Date().toISOString().split('T')[0];
    const startDateDate = new Date();
    startDateDate.setDate(startDateDate.getDate() - parseInt(range));
    const startDate = startDateDate.toISOString().split('T')[0];

    try {
        const res = await fetch(`${API_URL}/analytics?startDate=${startDate}&endDate=${endDate}`);
        const data = await res.json();

        // Update Summary
        document.getElementById('totalViews').innerText = data.summary.totalViews.toLocaleString();
        document.getElementById('totalSubs').innerText = data.summary.totalSubs.toLocaleString();
        document.getElementById('totalWatchTime').innerText = data.summary.totalWatchTime.toLocaleString();

        if (analyticsChart) {
            analyticsChart.destroy();
        }

        analyticsChart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: data.labels,
                datasets: [{
                    label: 'Views',
                    data: data.views,
                    borderColor: '#2c68f6',
                    backgroundColor: 'rgba(44, 104, 246, 0.1)',
                    fill: true,
                    tension: 0.4
                }, {
                    label: 'Subscribers Gained',
                    data: data.subs,
                    borderColor: '#27c93f',
                    backgroundColor: 'rgba(39, 201, 63, 0.1)',
                    fill: true,
                    tension: 0.4,
                    hidden: true // Hide by default to keep clean
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        position: 'top',
                    }
                },
                scales: {
                    y: {
                        beginAtZero: true,
                        grid: { color: '#f0f0f0' }
                    },
                    x: {
                        grid: { display: false }
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

async function showScheduleModal() {
    document.getElementById('scheduleModal').classList.remove('hidden');

    // Load Categories if not already
    const catSelect = document.getElementById('scheduleCategory');
    if (catSelect.options.length <= 1) {
        try {
            const res = await fetch(`${API_URL}/youtube/categories`);
            const cats = await res.json();
            if(cats && cats.length) {
                 cats.forEach(c => {
                     if (c.snippet.assignable) {
                         catSelect.innerHTML += `<option value="${c.id}">${c.snippet.title}</option>`;
                     }
                 });
            }
        } catch(e) {
            console.error("Failed to load categories", e);
        }
    }
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

        // Auto fill title from filename if empty
        const titleInput = document.getElementById("scheduleTitle");
        if(!titleInput.value) {
            titleInput.value = file.name.replace(/\.[^/.]+$/, "");
            updatePreview();
        }
    }
}

function updatePreview() {
    const title = document.getElementById("scheduleTitle").value || "Video Title";
    const desc = document.getElementById("scheduleDescription").value || "Video description...";

    document.getElementById("previewTitle").innerText = title;
    // Truncate desc for preview
    document.getElementById("previewDesc").innerText = desc.length > 80 ? desc.substring(0, 80) + "..." : desc;
}

async function submitSchedule() {
    if (!currentUser) { showLoginModal(); return; }

    const fileInput = document.getElementById('scheduleFile');
    const thumbnailInput = document.getElementById('scheduleThumbnail');
    const title = document.getElementById('scheduleTitle').value;
    const description = document.getElementById('scheduleDescription').value;
    const category = document.getElementById('scheduleCategory').value;
    const tags = document.getElementById('scheduleTags').value;
    const categoryId = document.getElementById('scheduleCategory').value;
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
    if (thumbnailInput && thumbnailInput.files[0]) {
        formData.append("thumbnail", thumbnailInput.files[0]);
    }
    formData.append("title", title);
    formData.append("description", description);
    if (category) {
        formData.append("categoryId", category);
    }
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
                if(thumbnailInput) thumbnailInput.value = '';
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

            if (data.enabled === false) {
                document.getElementById("verificationBanner").classList.remove("hidden");
                disableRestrictedFeatures();
            }

            checkInitialStatus();
        } else {
            showLoginModal();
        }
    } catch (e) { console.log("Guest"); }
}

function disableRestrictedFeatures() {
    const streamBtn = document.getElementById("btnGoLive");
    if(streamBtn) {
        streamBtn.disabled = true;
        streamBtn.title = "Verify email to stream";
        streamBtn.style.opacity = "0.5";
        streamBtn.style.cursor = "not-allowed";
    }

    // Disable YouTube connect in Settings (if visible)
    // We might need to select carefully if there are multiple.
    // Assuming the one in Settings -> Connections -> YouTube
    // Since view-settings is hidden initially, we might need to apply this when switching view?
    // Or just find all restricted buttons.
    // For now, simple check.
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
