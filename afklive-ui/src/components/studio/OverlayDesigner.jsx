import React, { useState, useRef, useEffect, useCallback } from 'react';

export default function OverlayDesigner({ video, playlistCount, isActive, onExportReady }) {
  const [elements, setElements] = useState([]);
  const [draggingId, setDraggingId] = useState(null);
  const containerRef = useRef(null);

  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 });
  const fileInputRef = useRef(null);

  const addText = () => {
    setElements([
      ...elements,
      { id: Date.now(), type: 'text', content: 'New Text', x: 50, y: 50, size: 48, color: '#FFFFFF', font: 'Inter' }
    ]);
  };

  const addImage = (e) => {
    const file = e.target.files[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (event) => {
      const dataUrl = event.target.result;
      const img = new Image();
      img.onload = () => {
        setElements(prev => [
          ...prev,
          {
            id: Date.now(),
            type: 'image',
            src: dataUrl,
            imgElement: img,
            x: 50,
            y: 50,
            width: img.width > 300 ? 300 : img.width,
            aspectRatio: img.height / img.width,
          }
        ]);
      };
      img.src = dataUrl;
    };
    reader.readAsDataURL(file);
    if (fileInputRef.current) fileInputRef.current.value = null;
  };

  const updateElement = (id, updates) => {
    setElements(elements.map(el => el.id === id ? { ...el, ...updates } : el));
  };

  const removeElement = (id) => {
    setElements(elements.filter(el => el.id !== id));
  };

  const moveLayerBackward = (index) => {
    if (index === 0) return;
    setElements(prev => {
      const newElements = [...prev];
      [newElements[index - 1], newElements[index]] = [newElements[index], newElements[index - 1]];
      return newElements;
    });
  };

  const moveLayerForward = (index) => {
    if (index === elements.length - 1) return;
    setElements(prev => {
      const newElements = [...prev];
      [newElements[index + 1], newElements[index]] = [newElements[index], newElements[index + 1]];
      return newElements;
    });
  };

  // Load template from local storage
  useEffect(() => {
    try {
      const saved = localStorage.getItem('afk-overlay-template');
      if (saved) {
        const parsed = JSON.parse(saved);
        const hydrated = parsed.map(el => {
          if (el.type === 'image') {
            const img = new Image();
            img.src = el.src;
            return { ...el, imgElement: img };
          }
          return el;
        });
        setElements(hydrated);
      }
    } catch (e) { console.error('Failed to load template', e); }
  }, []);

  // Save template to local storage
  useEffect(() => {
    if (elements.length > 0) {
      try {
        const serialized = elements.map(el => {
          const { imgElement, ...rest } = el;
          return rest;
        });
        localStorage.setItem('afk-overlay-template', JSON.stringify(serialized));
      } catch (e) { console.warn('Failed to save template', e); }
    } else {
      localStorage.removeItem('afk-overlay-template');
    }
  }, [elements]);

  // Drag logic
  const handlePointerDown = (e, id) => {
    setDraggingId(id);
    e.target.setPointerCapture(e.pointerId);
    const el = elements.find(el => el.id === id);
    if (el && containerRef.current) {
      const rect = containerRef.current.getBoundingClientRect();
      const mouseX = e.clientX - rect.left;
      const mouseY = e.clientY - rect.top;
      setDragOffset({ x: mouseX - el.x, y: mouseY - el.y });
    }
  };

  const handlePointerMove = (e) => {
    if (!draggingId || !containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    const x = (e.clientX - rect.left) - dragOffset.x;
    const y = (e.clientY - rect.top) - dragOffset.y;
    updateElement(draggingId, { x, y });
  };

  const handlePointerUp = () => {
    setDraggingId(null);
  };

  const exportToImage = useCallback(() => {
    if (elements.length === 0) return null;
    const canvas = document.createElement('canvas');
    canvas.width = 1920;
    canvas.height = 1080;
    const ctx = canvas.getContext('2d');
    
    // Clear background (transparent)
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    
    const container = containerRef.current;
    if (!container) return null;
    
    const scaleX = 1920 / container.clientWidth;
    const scaleY = 1080 / container.clientHeight;

    elements.forEach(el => {
      if (el.type === 'text') {
        const scaledSize = el.size * Math.min(scaleX, scaleY);
        ctx.font = `bold ${scaledSize}px "${el.font || 'Inter'}", sans-serif`;
        ctx.fillStyle = el.color;
        ctx.textBaseline = 'top';
        ctx.shadowColor = 'rgba(0,0,0,0.8)';
        ctx.shadowBlur = 10 * Math.min(scaleX, scaleY);
        ctx.fillText(el.content, el.x * scaleX, el.y * scaleY);
      } else if (el.type === 'image') {
        const drawWidth = el.width * scaleX;
        const drawHeight = (el.width * el.aspectRatio) * scaleY;
        ctx.drawImage(el.imgElement, el.x * scaleX, el.y * scaleY, drawWidth, drawHeight);
      }
    });

    return canvas.toDataURL('image/png');
  }, [elements]);

  // Expose export function to parent
  useEffect(() => {
    if (onExportReady) {
      onExportReady(() => exportToImage());
    }
  }, [exportToImage, onExportReady]);

  return (
    <div 
      ref={containerRef}
      onPointerMove={isActive ? handlePointerMove : undefined}
      onPointerUp={isActive ? handlePointerUp : undefined}
      className="relative w-full aspect-video bg-black overflow-hidden flex items-center justify-center group"
    >
      {/* 1. Main Video Element */}
      {video ? (
        <>
          <video 
            src={`/api/library/stream/${video.id}`} 
            className="absolute inset-0 w-full h-full object-contain pointer-events-auto" 
            autoPlay 
            loop 
            controls={!isActive}
          />
          {/* Subtle gradient at the bottom for readability */}
          {!isActive && (
            <div className="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/80 via-black/30 to-transparent p-6 pt-24 pointer-events-none transition-opacity duration-500">
              <h3 className="text-white font-bold text-2xl drop-shadow-lg tracking-tight">{video.title || video.originalFilename} {playlistCount > 1 ? `(+${playlistCount - 1} more)` : ''}</h3>
              <p className="text-white/70 text-sm mt-1 drop-shadow-md font-medium tracking-wide">
                {playlistCount > 1 ? 'Playlist (' + playlistCount + ' videos)' : `${video.videoCodec?.toUpperCase() || 'H264'} / ${video.audioCodec?.toUpperCase() || 'AAC'} · ${(video.fileSize / 1024 / 1024).toFixed(1)} MB`}
              </p>
            </div>
          )}
        </>
      ) : (
        <div className="absolute inset-0 flex flex-col items-center justify-center bg-gradient-to-br from-[#0a0a0a] to-[#111]">
          <div className="w-20 h-20 bg-white dark:bg-zinc-900/5 rounded-2xl flex items-center justify-center mb-6 border border-white/10 shadow-[0_0_40px_rgba(255,255,255,0.05)] backdrop-blur-sm">
            <i className="fa-solid fa-film text-3xl text-gray-500"></i>
          </div>
          <p className="font-bold text-white tracking-wide text-lg">Select a Source</p>
          <p className="text-sm text-gray-500 mt-2 max-w-xs text-center">Click a video from your Library to load it into the preview engine.</p>
        </div>
      )}

      {/* 2. Drag-and-Drop Elements */}
      {elements.map(el => {
        if (el.type === 'text') {
          return (
            <div
              key={el.id}
              onPointerDown={(e) => handlePointerDown(e, el.id)}
              style={{ left: el.x, top: el.y, fontSize: `${el.size}px`, fontFamily: el.font || 'Inter', color: el.color, textShadow: '0 4px 12px rgba(0,0,0,0.9)' }}
              className={`absolute cursor-move select-none font-black border-2 ${draggingId === el.id ? 'border-blue-500/80 bg-blue-500/10 backdrop-blur-sm' : 'border-transparent hover:border-white/30'} px-2 py-1 rounded-lg transition-all whitespace-nowrap z-40 shadow-2xl`}
            >
              {el.content}
            </div>
          );
        } else if (el.type === 'image') {
          return (
            <div
              key={el.id}
              onPointerDown={(e) => handlePointerDown(e, el.id)}
              style={{ left: el.x, top: el.y, width: `${el.width}px` }}
              className={`absolute cursor-move select-none border-2 ${draggingId === el.id ? 'border-blue-500/80 bg-blue-500/10 backdrop-blur-sm' : 'border-transparent hover:border-white/30'} p-1 rounded-lg transition-all z-40 shadow-2xl`}
            >
              <img src={el.src} alt="Overlay graphic" className="w-full h-auto pointer-events-none drop-shadow-2xl" draggable={false} />
            </div>
          );
        }
        return null;
      })}

      {/* 3. Floating HUD (Visible only when Custom Overlays is ON) */}
      {isActive && (
        <>
          {/* Top Navbar HUD */}
          <div className="absolute top-4 left-4 right-4 flex justify-between pointer-events-none z-50">
            {/* Left: Brand Pill */}
            <div className="backdrop-blur-xl bg-black/40 border border-white/10 px-4 py-2 rounded-2xl flex items-center pointer-events-auto shadow-[0_8px_30px_rgb(0,0,0,0.5)] transition-transform hover:scale-[1.02]">
              <i className="fa-solid fa-wand-magic-sparkles text-blue-400 mr-2 drop-shadow-[0_0_8px_rgba(96,165,250,0.8)]"></i>
              <span className="text-white font-bold text-[11px] uppercase tracking-[0.2em]">Stream Designer</span>
            </div>

            {/* Right: Actions Pill */}
            <div className="backdrop-blur-xl bg-black/40 border border-white/10 p-1.5 rounded-2xl flex gap-1 pointer-events-auto shadow-[0_8px_30px_rgb(0,0,0,0.5)]">
              <input 
                type="file" 
                accept="image/*" 
                ref={fileInputRef} 
                onChange={addImage} 
                className="hidden" 
              />
              <button onClick={() => fileInputRef.current?.click()} className="hover:bg-white dark:bg-zinc-900/10 text-white/90 hover:text-white px-4 py-2 rounded-xl text-xs font-bold transition-all flex items-center gap-2">
                <i className="fa-solid fa-image text-emerald-400"></i> Add Logo
              </button>
              <div className="w-px bg-white dark:bg-zinc-900/10 my-1 mx-1"></div>
              <button onClick={addText} className="hover:bg-white dark:bg-zinc-900/10 text-white/90 hover:text-white px-4 py-2 rounded-xl text-xs font-bold transition-all flex items-center gap-2">
                <i className="fa-solid fa-font text-blue-400"></i> Add Text
              </button>
            </div>
          </div>

          {/* Right Sidebar HUD */}
          {elements.length > 0 && (
            <div className="absolute top-20 right-4 bottom-4 w-72 backdrop-blur-xl bg-black/50 border border-white/10 rounded-3xl p-5 overflow-y-auto pointer-events-auto shadow-[0_8px_30px_rgb(0,0,0,0.6)] custom-scrollbar z-50 transition-all duration-300">
              <div className="flex items-center justify-between mb-5">
                <h4 className="text-[10px] font-black text-white/50 uppercase tracking-widest">Active Layers</h4>
                <span className="bg-blue-500/20 text-blue-400 text-[9px] font-bold px-2 py-0.5 rounded-full">{elements.length}</span>
              </div>
              
              <div className="space-y-3">
                {elements.map((el, index) => (
                  <div key={el.id} className="bg-white dark:bg-zinc-900/5 hover:bg-white dark:bg-zinc-900/10 transition-colors p-4 rounded-2xl border border-white/5 group">
                    <div className="flex justify-between items-center mb-3">
                      <span className="text-white/90 text-xs font-bold flex items-center gap-2">
                        {el.type === 'text' ? <i className="fa-solid fa-t text-blue-400/80"></i> : <i className="fa-solid fa-image text-emerald-400/80"></i>}
                        {el.type === 'text' ? 'Text Overlay' : 'Image Overlay'}
                      </span>
                      <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                        <button onClick={() => moveLayerBackward(index)} disabled={index === 0} className="text-white/40 hover:text-white disabled:opacity-20 p-1.5 text-[10px]" title="Send Backward">
                          <i className="fa-solid fa-arrow-down"></i>
                        </button>
                        <button onClick={() => moveLayerForward(index)} disabled={index === elements.length - 1} className="text-white/40 hover:text-white disabled:opacity-20 p-1.5 text-[10px]" title="Bring Forward">
                          <i className="fa-solid fa-arrow-up"></i>
                        </button>
                        <div className="w-px h-3 bg-white dark:bg-zinc-900/10 mx-1"></div>
                        <button onClick={() => removeElement(el.id)} className="text-red-400/50 hover:text-red-400 text-xs p-1.5" title="Delete Layer">
                          <i className="fa-solid fa-trash"></i>
                        </button>
                      </div>
                    </div>

                    {el.type === 'text' ? (
                      <>
                        <input 
                          type="text" 
                          value={el.content} 
                          onChange={e => updateElement(el.id, { content: e.target.value })}
                          className="w-full bg-black/40 border border-white/10 rounded-xl px-3 py-2 text-sm text-white mb-2 outline-none focus:border-blue-500/50 focus:ring-1 focus:ring-blue-500/50 transition-all placeholder-white/30"
                          placeholder="Enter text..."
                        />
                        <select
                          value={el.font || 'Inter'}
                          onChange={e => updateElement(el.id, { font: e.target.value })}
                          className="w-full bg-black/40 border border-white/10 rounded-xl px-3 py-1.5 text-xs text-white mb-3 outline-none focus:border-blue-500/50 appearance-none cursor-pointer"
                        >
                          <option value="Inter">Inter</option>
                          <option value="Roboto">Roboto</option>
                          <option value="Montserrat">Montserrat</option>
                          <option value="Bebas Neue">Bebas Neue</option>
                          <option value="Impact">Impact</option>
                          <option value="Arial">Arial</option>
                        </select>
                        <div className="flex items-center gap-3 bg-black/20 p-2 rounded-xl border border-white/5">
                          <input 
                            type="color" 
                            value={el.color} 
                            onChange={e => updateElement(el.id, { color: e.target.value })}
                            className="w-7 h-7 rounded-lg border-2 border-white/20 p-0 cursor-pointer bg-transparent shrink-0"
                          />
                          <input 
                            type="range" 
                            min="16" max="200" 
                            value={el.size} 
                            onChange={e => updateElement(el.id, { size: parseInt(e.target.value) })}
                            className="flex-1 accent-blue-500 h-1 bg-white dark:bg-zinc-900/10 rounded-full appearance-none cursor-pointer"
                          />
                        </div>
                      </>
                    ) : (
                      <div className="flex flex-col gap-2 bg-black/20 p-3 rounded-xl border border-white/5">
                        <div className="flex justify-between items-center">
                          <label className="text-[10px] text-white/50 font-bold tracking-widest">SCALE</label>
                          <span className="text-xs font-mono text-white/70">{el.width}px</span>
                        </div>
                        <input 
                          type="range" 
                          min="50" max="1000" 
                          value={el.width} 
                          onChange={e => updateElement(el.id, { width: parseInt(e.target.value) })}
                          className="w-full accent-emerald-500 h-1 bg-white dark:bg-zinc-900/10 rounded-full appearance-none cursor-pointer"
                        />
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
