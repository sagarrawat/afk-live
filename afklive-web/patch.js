const fs = require('fs');

let pricingPath = 'src/main/java/com/afklive/streamer/endpoint/PricingController.java';
let pricingContent = fs.readFileSync(pricingPath, 'utf8');

// remove calculateFinalRate from PricingController, replace with appConfigService.calculateFinalRate
pricingContent = pricingContent.replace(
    /public java.math.BigDecimal calculateFinalRate[\s\S]*?return rate.setScale\(4, java.math.RoundingMode.HALF_UP\);\n    }/,
    ""
);
pricingContent = pricingContent.replace(
    /java.math.BigDecimal rate = calculateFinalRate\(principal, quality, destCount, poweredBy\);/,
    "java.math.BigDecimal rate = java.math.BigDecimal.ZERO;\n        if (principal != null) {\n            User user = userService.getOrCreateUser(SecurityUtils.getEmail(principal));\n            rate = appConfigService.calculateFinalRate(user, quality, destCount, poweredBy);\n        }"
);
fs.writeFileSync(pricingPath, pricingContent);

let appConfigPath = 'src/main/java/com/afklive/streamer/service/AppConfigService.java';
let appConfigContent = fs.readFileSync(appConfigPath, 'utf8');

let newMethod = `
    public BigDecimal calculateFinalRate(com.afklive.streamer.model.User user, int quality, int destCount, boolean poweredBy) {
        String currency = user.getCurrency() != null ? user.getCurrency() : "INR";
        BigDecimal baseRate = getCurrentHourlyRate(currency);
        BigDecimal qualityMultiplier = getQualityMultiplier(quality);
        
        // Base rate * Quality * Destinations
        BigDecimal rate = baseRate.multiply(qualityMultiplier).multiply(new BigDecimal(Math.max(1, destCount)));
        
        // Let's not use PlanService directly here to avoid circular dep, wait we can just pass plan discount
        // Actually, passing planDiscount is better.
        // I will change the signature.
    }
`;
