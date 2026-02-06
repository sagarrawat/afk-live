// Modern Toast Notification System
// Inspired by Buffer/Stripe notifications

function showToast(message, type = 'info') {
    // Create container if not exists
    let container = document.getElementById('toast-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'toast-container';
        container.style.cssText = `
            position: fixed;
            bottom: 24px;
            right: 24px;
            z-index: 9999;
            display: flex;
            flex-direction: column;
            gap: 10px;
            pointer-events: none; /* Let clicks pass through container */
        `;
        document.body.appendChild(container);
    }

    // Prevent duplicates
    const existing = Array.from(container.children).find(child => {
        const msgSpan = child.querySelector('.toast-message');
        return msgSpan && msgSpan.innerHTML === message;
    });
    if (existing) return;

    // Create toast element
    const toast = document.createElement('div');

    // Icon based on type
    let icon = '';
    let color = '';
    let bg = '';

    switch(type) {
        case 'success':
            icon = '<i class="fa-solid fa-check-circle"></i>';
            color = '#00875a'; // Green
            bg = '#e3fcef';
            break;
        case 'error':
            icon = '<i class="fa-solid fa-circle-exclamation"></i>';
            color = '#de350b'; // Red
            bg = '#ffebe6';
            break;
        case 'warning':
            icon = '<i class="fa-solid fa-triangle-exclamation"></i>';
            color = '#ff991f'; // Orange
            bg = '#fff7d6';
            break;
        default:
            icon = '<i class="fa-solid fa-circle-info"></i>';
            color = '#42526e'; // Gray/Blue
            bg = '#ffffff';
    }

    toast.style.cssText = `
        background: ${bg};
        color: ${color};
        border-left: 4px solid ${color};
        padding: 16px 20px;
        border-radius: 4px;
        box-shadow: 0 4px 12px rgba(0,0,0,0.15);
        font-family: 'Inter', sans-serif;
        font-size: 14px;
        font-weight: 500;
        display: flex;
        align-items: center;
        gap: 12px;
        min-width: 300px;
        transform: translateX(100%);
        transition: transform 0.3s cubic-bezier(0.2, 0.8, 0.2, 1);
        pointer-events: auto;
    `;

    toast.innerHTML = `
        <span style="font-size: 18px;">${icon}</span>
        <span class="toast-message" style="flex: 1;">${message}</span>
        <button onclick="this.parentElement.remove()" style="background:none;border:none;cursor:pointer;color:inherit;opacity:0.6;"><i class="fa-solid fa-xmark"></i></button>
    `;

    container.appendChild(toast);

    // Animate in
    requestAnimationFrame(() => {
        toast.style.transform = 'translateX(0)';
    });

    // Auto dismiss
    setTimeout(() => {
        toast.style.transform = 'translateX(120%)';
        toast.addEventListener('transitionend', () => {
            if(toast.parentElement) toast.remove();
        });
    }, 4000);
}

// Make it global
window.showToast = showToast;
