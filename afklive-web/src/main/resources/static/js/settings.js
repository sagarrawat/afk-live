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
            // Check for payment redirect params
            const urlParams = new URLSearchParams(window.location.search);
            const paymentStatus = urlParams.get('payment_status');

            if (paymentStatus === 'pending') {
                // Ideally poll backend for status update, or wait a bit then refresh user
                // For MVP, we reload user and check if plan changed
                showToast("Verifying payment...", "info");
                setTimeout(async () => {
                    await this.loadUser();
                    if (this.user && this.user.plan && this.user.plan.name === 'Essentials') {
                        showToast("Payment Successful! Plan Upgraded.", "success");
                    } else {
                        showToast("Payment processing or failed. Please check back later.", "warning");
                    }
                    // Clean URL
                    window.history.replaceState({}, document.title, window.location.pathname + "?view=settings");
                }, 2000);
            }

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
            if (!await Alpine.store('modal').confirm("Are you sure you want to disconnect this channel?", "Disconnect Channel")) return;
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
            const planId = this.selectedPlan.id;

            // Direct upgrade for Free plan (downgrade/switch) if logic permits,
            // but usually this function is called for Paid upgrades.
            if (planId === 'FREE') {
               // Call cancel/downgrade logic
               this.cancelSubscription();
               return;
            }

            try {
                // 1. Initiate Payment
                const res = await apiFetch('/api/payment/initiate', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ planId: planId })
                });
                const data = await res.json();

                if (data.redirectUrl) {
                    // 2. Redirect to PhonePe
                    window.location.href = data.redirectUrl;
                } else {
                    showToast(data.message || "Payment initiation failed", "error");
                }
            } catch (e) {
                showToast("Payment Error: " + e.message, "error");
            }
        },

        async cancelSubscription() {
            if (!await Alpine.store('modal').confirm("Are you sure you want to cancel your subscription? You will lose access to premium features.", "Cancel Subscription")) return;
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
