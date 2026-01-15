// Navigation and Mobile Menu
document.addEventListener('DOMContentLoaded', () => {
    const hamburger = document.querySelector('.hamburger');
    const sidebar = document.querySelector('.sidebar');
    const contentArea = document.querySelector('.content-area');

    if (hamburger && sidebar) {
        hamburger.addEventListener('click', (e) => {
            e.stopPropagation();
            sidebar.classList.toggle('open');
        });

        // Close sidebar when clicking outside on mobile
        contentArea.addEventListener('click', () => {
            if (window.innerWidth <= 768 && sidebar.classList.contains('open')) {
                sidebar.classList.remove('open');
            }
        });
    }
});

// Toast Notification System
function showToast(message, type = 'info') {
    let container = document.querySelector('.toast-container');
    if (!container) {
        container = document.createElement('div');
        container.className = 'toast-container';
        document.body.appendChild(container);
    }

    const toast = document.createElement('div');
    toast.className = `toast ${type}`;

    let icon = '';
    if (type === 'success') icon = '<i class="fa-solid fa-check-circle" style="color:#27c93f"></i>';
    if (type === 'error') icon = '<i class="fa-solid fa-circle-exclamation" style="color:#e02424"></i>';
    if (type === 'info') icon = '<i class="fa-solid fa-info-circle" style="color:#2c68f6"></i>';

    toast.innerHTML = `
        ${icon}
        <div class="toast-content">${message}</div>
        <i class="fa-solid fa-times toast-close"></i>
    `;

    container.appendChild(toast);

    // Auto remove
    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateX(100%)';
        toast.style.transition = 'all 0.3s ease-in';
        setTimeout(() => toast.remove(), 300);
    }, 5000);

    // Click to remove
    toast.querySelector('.toast-close').addEventListener('click', () => toast.remove());
}

// Override window.alert
window.alert = function(msg) {
    showToast(msg, 'info');
};
