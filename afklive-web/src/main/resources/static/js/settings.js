document.addEventListener('alpine:init', () => {
    Alpine.data('settingsStudio', () => ({
        activeTab: 'general',
        user: null,
        channels: [],
        plans: [],
        isLoading: false,
        paymentModalOpen: false,
        selectedPlan: null,
        isProcessingPayment: false,

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
                const res = await apiFetch('/api/pricing?country=IN');
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

            // Parse price string (e.g. ₹499) to number for payment
            let amount = 0;
            try {
                amount = parseInt(this.selectedPlan.price.replace(/[^0-9]/g, '')) * 100; // to paise
            } catch(e) {}

            if (amount <= 0) {
                 // Fallback for free or error
                 this.paymentModalOpen = false;
                 return;
            }

            // Show Loader
            const btn = document.querySelector('button[x-text="\'Confirm & Pay\'"]') || document.querySelector('#paymentModal button.bg-blue-600');
            // Since Alpine component scope, standard DOM query might be tricky if not refs.
            // But we can set a state variable if we bound it.
            // Let's use global loader for simplicity or assume button text changes if bound.
            // Adding a loading state to the component
            this.isProcessingPayment = true;

            try {
                const res = await apiFetch('/api/payment/initiate', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ amount: amount, planId: this.selectedPlan.id })
                });
                const data = await res.json();

                if (data.redirectUrl) {
                    window.location.href = data.redirectUrl;
                } else {
                    showToast(data.message || "Payment initiation failed", "error");
                    this.isProcessingPayment = false;
                }
            } catch (e) {
                showToast("Payment failed", "error");
                this.isProcessingPayment = false;
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
