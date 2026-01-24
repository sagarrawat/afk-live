document.addEventListener('alpine:init', () => {
    Alpine.data('settingsStudio', () => ({
        activeTab: 'general',
        user: null,
        channels: [],
        plans: [],
        isLoading: false,
        paymentModalOpen: false,
        selectedPlan: null,

        async init() {
            await this.loadUser();
            await this.loadChannels();
            // Listen for channel changes from global events if any
            window.addEventListener('destination-added', () => this.loadChannels());
        },

        async loadUser() {
            try {
                const res = await apiFetch(`/api/user-info?_=${Date.now()}`);
                this.user = await res.json();
            } catch (e) {
                console.error("Failed to load user", e);
            }
        },

        async loadChannels() {
            try {
                const res = await apiFetch(`/api/channels`);
                this.channels = await res.json();
            } catch (e) {
                console.error("Failed to load channels", e);
            }
        },

        async loadPlans() {
            this.isLoading = true;
            try {
                const res = await apiFetch('/api/pricing?country=US');
                const data = await res.json();
                this.plans = data.plans || [];
            } catch (e) {
                console.error("Failed to load plans", e);
            } finally {
                this.isLoading = false;
            }
        },

        switchTab(tab) {
            this.activeTab = tab;
            if (tab === 'plans' && this.plans.length === 0) {
                this.loadPlans();
            }
        },

        // Channel Actions
        openConnectModal() {
            // Reuse global function from app.js
            if (window.openConnectModal) window.openConnectModal();
        },

        async removeChannel(id) {
            if (!confirm("Are you sure you want to disconnect this channel?")) return;
            try {
                await apiFetch(`/api/channels/${id}`, { method: 'DELETE' });
                showToast("Channel disconnected", "success");
                this.loadChannels();
            } catch (e) {
                showToast("Failed to disconnect", "error");
            }
        },

        // Subscription Actions
        get storagePercentage() {
            if (!this.user || !this.user.plan) return 0;
            return Math.min(100, (this.user.plan.storageUsed / this.user.plan.storageLimit) * 100);
        },

        get formattedStorage() {
            if (!this.user || !this.user.plan) return "0/0 MB";
            const used = (this.user.plan.storageUsed / 1024 / 1024).toFixed(1);
            const limit = (this.user.plan.storageLimit / 1024 / 1024).toFixed(0);
            return `${used} / ${limit} MB`;
        },

        openPayment(plan) {
            this.selectedPlan = plan;
            this.paymentModalOpen = true;
        },

        async processUpgrade() {
            if (!this.selectedPlan) return;
            try {
                await apiFetch('/api/pricing/upgrade', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ planId: this.selectedPlan.id })
                });
                showToast("Plan upgraded successfully!", "success");
                this.paymentModalOpen = false;
                this.loadUser(); // Refresh user info
            } catch (e) {
                showToast("Upgrade failed", "error");
            }
        },

        async cancelSubscription() {
            if (!confirm("Are you sure you want to cancel your subscription? You will lose access to premium features.")) return;
            try {
                await apiFetch('/api/pricing/cancel', { method: 'POST' });
                showToast("Subscription cancelled", "info");
                this.loadUser();
            } catch (e) {
                showToast("Cancellation failed", "error");
            }
        }
    }));
});
