const API_URL = "/api"; 
let uploadedFileName = "";
let currentUser = null; 
let logInterval = null; // Global reference for the live console timer

document.addEventListener("DOMContentLoaded", async () => {
    await fetchUserInfo(); // This kicks off the login check and subsequent status sync
    
    const userHeader = document.getElementById("userHeader");
    if (userHeader) {
        userHeader.style.cursor = "pointer"; // Make it look clickable
        userHeader.addEventListener("click", () => {
            if (!currentUser) showLoginModal();
        });
    }
    
    loadGlobalSettings();
    loadLibrary();
    // --- ATTACH EVENT LISTENERS ---
    document.getElementById("dropZone").addEventListener("click", () => document.getElementById("videoFile").click());
    document.getElementById("videoFile").addEventListener("change", handleFileUpload);
    
    document.getElementById("musicDropZone").addEventListener("click", () => document.getElementById("musicFile").click());
    document.getElementById("musicFile").addEventListener("change", handleMusicUpload);

    const radioNow = document.getElementById("radioNow");
    const radioSchedule = document.getElementById("radioSchedule");
    if(radioNow) radioNow.addEventListener("change", toggleScheduleUI);
    if(radioSchedule) radioSchedule.addEventListener("change", toggleScheduleUI);

    document.getElementById("volumeSlider").addEventListener("input", (e) => {
        document.getElementById("volValue").innerText = e.target.value + "%";
        document.getElementById("audioPreview").volume = e.target.value / 100;
    });

    // --- INIT SETTINGS ---
    loadGlobalSettings();
});

// --- STATE PERSISTENCE & LOG POLLING ---

/**
 * Checks if a stream is already running for this user on the server.
 * Restores the UI state if the user refreshed the page or logged back in.
 */
async function checkInitialStatus() {
    try {
        const res = await fetch(`${API_URL}/status`);
        const data = await res.json(); 
        
        // If data exists and isLive is true, the backend process is active
        if (data.success && data.data?.live) {
            log("🔄 Active session detected. Synchronizing UI...");
            uploadedFileName = data.fileName; 
            document.getElementById("uploadText").innerText = "✅ " + data.fileName;
            document.getElementById("dropZone").classList.add("uploaded");
            document.getElementById("streamKey").value = data.streamKey; 
            
            if (data.musicName) {
                window.uploadedMusicName = data.musicName;
                document.getElementById("musicText").innerText = "✅ " + data.musicName;
                document.getElementById("audioControlPanel").classList.remove("hidden");
                
                if (data.musicVolume) {
                    const volPercent = Math.round(data.musicVolume * 100);
                    document.getElementById("volumeSlider").value = volPercent;
                    document.getElementById("volValue").innerText = volPercent + "%";
                }
            }
            
            setLiveState(true);
//            startLogPolling(); // Connect to the live console logs
        }
    } catch (err) {
        console.log("System Status: Idle / Ready for new stream.");
    }
}

/**
 * Periodically fetches the latest FFmpeg output from the server.
 */
function startLogPolling() {
    if (logInterval) clearInterval(logInterval);
    
    logInterval = setInterval(async () => {
        try {
            const res = await fetch(`${API_URL}/logs`);
            if (!res.ok) return;
            const logs = await res.json(); // Expected format: Array of strings
            
            const consoleDiv = document.getElementById("console");
            consoleDiv.innerHTML = ""; // Clear for fresh batch of latest lines
            
            logs.forEach(line => {
                const entry = document.createElement("div");
                entry.className = "log-entry";
                // Color coding specific FFmpeg keywords for visibility
                let formattedLine = line.replace(/(speed=|bitrate=|fps=)/g, '<span style="color:var(--primary-red)">$1</span>');
                entry.innerHTML = `<span class="log-time">[FFMPEG]</span> ${formattedLine}`;
                consoleDiv.appendChild(entry);
            });
            
            consoleDiv.scrollTop = consoleDiv.scrollHeight; // Auto-scroll to bottom
        } catch (err) {
            console.error("Log fetch failed", err);
        }
    }, 2000); // Poll every 2 seconds
}

function stopLogPolling() {
    if (logInterval) {
        clearInterval(logInterval);
        logInterval = null;
    }
}

// --- CORE STREAMING LOGIC ---

async function submitJob(fileNameFromLibrary) {
    const targetFile = fileNameFromLibrary || uploadedFileName;
    const key = document.getElementById("streamKey").value;
    const btn = document.getElementById("btnGoLive");
    
    if (!targetFile) return log("⚠️ Select a video from the library.");    
    if (!key) return log("⚠️ Please enter a Stream Key.");

    log(`🚀 Initiating stream for: ${targetFile}`);
    
    btn.disabled = true;
    btn.innerText = "Starting Engine...";
    showPreviewLoader("Initializing Stream...");

    const formData = new FormData();
    formData.append("streamKey", key);
    formData.append("fileName", targetFile);

    if (window.uploadedMusicName) {
        formData.append("musicName", window.uploadedMusicName);
        const volPercent = document.getElementById("volumeSlider").value;
        formData.append("musicVolume", (volPercent / 100).toFixed(2));
    }

    try {
        const res = await fetch(`${API_URL}/start`, { method: "POST", body: formData });
        const data = await res.json();
        
        if (data.success) {
            log(`✅ ${data.message}`);
            setLiveState(true, data.data);
//            startLogPolling();
        } else {
            throw new Error(data.message || 'Request failed');
        }
    } catch (err) {
        log(`❌ Error: ${err.message}`);
        btn.disabled = false;
        btn.innerText = "GO LIVE NOW";
        showPreviewPlaceholder("Connection Failed");
    }
}

async function stopStream() {
    log("🛑 Sending Stop Signal...");
    try {
        const res = await fetch(`${API_URL}/stop`, { method: "POST" });
        const data = await res.json();
        if (data.success) {
            log(`✅ ${data.message}`);
            setLiveState(false);
            stopLogPolling();
        }
    } catch (err) {
        log("❌ Error stopping stream.");
    }
}

// --- FILE UPLOADS ---

async function handleFileUpload(e) {
    if (!currentUser) { 
        e.preventDefault(); 
        showLoginModal(); 
        return; 
    }
    
    const file = e.target.files[0];
    if (!file) return;

    log(`📤 Uploading Video: ${file.name}...`);
    document.getElementById("uploadText").innerText = "Uploading to Cloud...";
    
    const formData = new FormData();
    formData.append("file", file);

    try {
        const res = await fetch(`${API_URL}/upload`, { method: "POST", body: formData });
        if (res.ok) {
            const data = await res.json();
            uploadedFileName = data.data;
            startConversionProcess(uploadedFileName);
            document.getElementById("uploadText").innerText = "✅ " + file.name;
            document.getElementById("dropZone").classList.add("uploaded");
            log("✅ Video Upload Complete.");
        }
    } catch (err) {
        log("❌ Upload Failed.");
        document.getElementById("uploadText").innerText = "❌ Try Again.";
    }
}

async function handleMusicUpload(e) {
    if (!currentUser) { showLoginModal(); return; }
    
    const file = e.target.files[0];
    if (!file) return;

    document.getElementById("audioControlPanel").classList.remove("hidden");
    const audioPlayer = document.getElementById("audioPreview");
    audioPlayer.src = URL.createObjectURL(file);

    log(`🎵 Uploading Music: ${file.name}...`);
    const formData = new FormData();
    formData.append("file", file);

    const res = await fetch(`${API_URL}/upload`, { method: "POST", body: formData });
    if (res.ok) {
        const data = await res.json();
            window.uploadedMusicName = data.data;
        document.getElementById("musicText").innerText = "✅ " + file.name;
        log("✅ Audio Track Ready.");
    }
}

// --- AUTH & ACCOUNT ---

async function fetchUserInfo() {
    try {
        const res = await fetch('/api/user-info');
        const data = await res.json();
        
        if (data.email) {
            currentUser = data;
            updateUIForUser(); 
            log("👤 Logged in as: " + currentUser.name);
            checkInitialStatus(); // Sync state now that we know who the user is
        } else {
            currentUser = null;
            log("👋 Welcome Guest. Please login to continue.");
        }
    } catch (err) {
        console.log("Guest Mode Active");
    }
}

function updateUIForUser() {
    const container = document.getElementById("userHeader");
    if (!container) return;
    container.innerHTML = `
        <span style="margin-right:10px; font-size:0.8rem;">${currentUser.name}</span>
        <img src="${currentUser.picture}" style="width:32px; height:32px; border-radius:50%; border:1px solid #444;">
        <a href="/logout" style="margin-left:15px; color:#555; text-decoration:none; font-size:1.2rem;" title="Logout">⏻</a>
    `;
}

// --- UI COMPONENT HELPERS ---

function setLiveState(isLive) {
    const badge = document.getElementById("liveBadge");
    const container = document.getElementById("previewContainer");
    const btnGoLive = document.getElementById("btnGoLive");
    const activeStreamControls = document.getElementById("activeStreamControls"); // Select the wrapper
    const headerDot = document.getElementById("headerLiveDot");

    if (isLive) {
        // Update Badge and Header
        if(badge) {
            badge.className = "badge live w-100 mb-2 text-center";
            badge.innerText = "STREAMING NOW";
        }
        if(headerDot) headerDot.classList.remove("hidden");
        
        // Update Monitor Preview
        container.innerHTML = `
            <div style="color: var(--success); font-size: 3rem; margin-bottom: 10px;">📡</div>
            <div style="color: white; font-weight: bold;">STREAMING ACTIVE</div>
            <div style="color: #666; font-size: 0.8rem; margin-top: 5px;">Data being pushed to YouTube</div>
        `;

        // Logic Fix: Hide the Go Live button, show the entire Controls wrapper
        if (btnGoLive) btnGoLive.classList.add("hidden");
        if (activeStreamControls) activeStreamControls.classList.remove("hidden");
    } else {
        if(badge) {
            badge.className = "badge";
            badge.innerText = "OFFLINE";
        }
        if(headerDot) headerDot.classList.add("hidden");

        showPreviewPlaceholder("Ready to Broadcast");

        // Logic Fix: Show the Go Live button, hide the entire Controls wrapper
        if (btnGoLive) {
            btnGoLive.classList.remove("hidden");
            btnGoLive.disabled = false;
            btnGoLive.innerText = "GO LIVE NOW";
        }
        if (activeStreamControls) activeStreamControls.classList.add("hidden");
    }
}

function showPreviewLoader(text) {
    document.getElementById("previewContainer").innerHTML = 
        `<div class="spinner"></div><div class="pulse-text" style="color: #aaa; font-size: 0.9rem; margin-top:15px;">${text}</div>`;
}

function showPreviewPlaceholder(text) {
    document.getElementById("previewContainer").innerHTML = `
        <div class="corner-marker tl"></div><div class="corner-marker tr"></div>
        <div class="corner-marker bl"></div><div class="corner-marker br"></div>
        <div class="standby-content">
            <div class="standby-icon">🎥</div>
            <div class="standby-text">SIGNAL STANDBY</div>
            <div class="standby-sub">[ ${text} ]</div>
        </div>`;
}

function log(msg) {
    const consoleDiv = document.getElementById("console");
    if(!consoleDiv) return;
    const entry = document.createElement("div");
    entry.className = "log-entry";
    entry.innerHTML = `<span class="log-time">[${new Date().toLocaleTimeString()}]</span> ${msg}`;
    consoleDiv.appendChild(entry);
    consoleDiv.scrollTop = consoleDiv.scrollHeight;
}

function showLoginModal() {
    const modal = document.getElementById("loginModal");
    modal.classList.remove("hidden");
    modal.style.display = "flex";
}

function closeModal() {
    const modal = document.getElementById("loginModal");
    modal.classList.add("hidden");
    modal.style.display = "none";
}

function toggleScheduleUI() {
    const isSchedule = document.getElementById("radioSchedule").checked;
    const scheduleInput = document.getElementById("scheduleInputDiv");
    const btn = document.getElementById("btnGoLive");
    
    if (isSchedule) {
        scheduleInput.classList.remove("hidden");
        btn.innerText = "Schedule Stream";
    } else {
        scheduleInput.classList.add("hidden");
        btn.innerText = "GO LIVE NOW";
    }
}

function switchView(viewName) {
    document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
    document.getElementById(`nav-${viewName}`).classList.add('active');
    document.querySelectorAll('.view-section').forEach(el => el.classList.add('hidden'));
    document.getElementById(`view-${viewName}`).classList.remove('hidden');
    document.getElementById('headerTitle').innerText = viewName.toUpperCase();
}

function loadGlobalSettings() {
    const savedKey = localStorage.getItem('afk_stream_key');
    if(savedKey) {
        if(document.getElementById('streamKey')) document.getElementById('streamKey').value = savedKey;
        if(document.getElementById('savedKeyInput')) document.getElementById('savedKeyInput').value = savedKey;
        log("🔑 Stream Key loaded from LocalStorage.");
    }
}

function saveGlobalSettings() {
    const key = document.getElementById('savedKeyInput').value;
    if(key) {
        localStorage.setItem('afk_stream_key', key);
        alert('Settings Saved.');
        if(document.getElementById('streamKey')) document.getElementById('streamKey').value = key;
    }
}

async function startConversionProcess(fileName) {
    // UI Changes
    document.getElementById("uploadUI").classList.add("hidden");
    document.getElementById("conversionOverlay").classList.remove("hidden");
    document.getElementById("btnGoLive").disabled = true;
    document.getElementById("btnGoLive").innerText = "WAITING FOR OPTIMIZATION...";

    // Tell Backend to start converting
    await fetch(`${API_URL}/convert?fileName=${fileName}`, { method: "POST" });

    // Poll for status
    const poll = setInterval(async () => {
        const res = await fetch(`${API_URL}/convert/status?fileName=${fileName}`);
        const progress = await res.json();
        
        document.getElementById("conversionBar").style.width = progress + "%";
        
        if (progress >= 100) {
            clearInterval(poll);
            uploadedFileName = fileName; // Use the optimized file
            finishConversionUI();
        }
    }, 2000);
}

function finishConversionUI() {
    document.getElementById("conversionOverlay").classList.add("hidden");
    document.getElementById("uploadUI").classList.remove("hidden");
    document.getElementById("uploadText").innerText = "✅ Optimized & Ready";
    document.getElementById("btnGoLive").disabled = false;
    document.getElementById("btnGoLive").innerText = "GO LIVE NOW";
}

async function loadLibrary() {
    try {
        const res = await fetch('/api/library');
        const files = await res.json();
        const grid = document.getElementById('libraryGrid');
        grid.innerHTML = '';

        files.forEach(file => {
            const isReady = true;
            const card = document.createElement('div');
            card.className = 'card';
            card.style.padding = '15px';
            card.style.background = '#181818';
            
            card.innerHTML = `
                <div style="font-size: 1.2rem; margin-bottom: 10px;">${isReady ? '🎬' : '⚙️'}</div>
                <div style="font-size: 0.8rem; font-weight: bold; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${file}">
                    ${file.replace('ready_', '')}
                </div>
                <div style="margin-top: 10px; display: flex; gap: 5px;">
                    ${isReady ? 
                        `<button class="btn btn-primary" style="padding: 5px; font-size: 0.7rem;" onclick="submitJob('${file}')">GO LIVE</button>` : 
                        `<span class="badge" style="width: 100%">PROCESSING...</span>`
                    }
                </div>
            `;
            grid.appendChild(card);
        });
    } catch (err) {
        log("❌ Failed to load library grid.");
    }
}
