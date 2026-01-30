document.addEventListener('alpine:init', () => {
    Alpine.store('modal', {
        open: false,
        type: 'alert', // 'alert', 'confirm', 'prompt'
        title: '',
        message: '',
        inputValue: '',
        placeholder: '',
        resolve: null,

        // Trigger an Alert
        alert(title, message) {
            this.type = 'alert';
            this.title = title;
            this.message = message;
            this.open = true;
            return new Promise((resolve) => {
                this.resolve = resolve;
            });
        },

        // Trigger a Confirmation
        confirm(message, title = 'Are you sure?') {
            this.type = 'confirm';
            this.title = title;
            this.message = message;
            this.open = true;
            return new Promise((resolve) => {
                this.resolve = resolve;
            });
        },

        // Trigger a Prompt
        prompt(title, placeholder = '', defaultValue = '') {
            this.type = 'prompt';
            this.title = title;
            this.message = ''; // Prompt usually uses title as the question
            this.placeholder = placeholder;
            this.inputValue = defaultValue;
            this.open = true;

            // Focus input on next tick
            setTimeout(() => {
                const input = document.getElementById('modal-input');
                if (input) input.focus();
            }, 100);

            return new Promise((resolve) => {
                this.resolve = resolve;
            });
        },

        cancel() {
            this.open = false;
            if (this.resolve) {
                if (this.type === 'confirm') this.resolve(false);
                else this.resolve(null);
            }
            this.reset();
        },

        accept() {
            this.open = false;
            if (this.resolve) {
                if (this.type === 'confirm') this.resolve(true);
                else if (this.type === 'prompt') this.resolve(this.inputValue);
                else this.resolve(true);
            }
            this.reset();
        },

        reset() {
            this.title = '';
            this.message = '';
            this.inputValue = '';
            this.resolve = null;
        }
    });
});
