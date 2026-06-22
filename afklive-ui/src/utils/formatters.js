export const formatMoney = (amount) => {
    if (amount === undefined || amount === null || isNaN(amount)) return "0.00";
    return (Math.floor(amount * 100) / 100).toFixed(2);
};
