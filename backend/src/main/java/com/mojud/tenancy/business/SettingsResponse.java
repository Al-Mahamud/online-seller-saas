package com.mojud.tenancy.business;

record SettingsResponse(StockCommitPoint stockCommitPoint, int lowStockDefault, String orderNumberPrefix,
        String invoiceFooter, SearchTheme searchTheme) {

    static SettingsResponse of(BusinessSettings settings) {
        return new SettingsResponse(settings.getStockCommitPoint(), settings.getLowStockDefault(),
                settings.getOrderNumberPrefix(), settings.getInvoiceFooter(), settings.getSearchTheme());
    }
}
