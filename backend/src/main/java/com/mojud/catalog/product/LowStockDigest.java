package com.mojud.catalog.product;

import com.mojud.catalog.system.LowStockDigests;
import com.mojud.notifications.MemberMail;
import com.mojud.shared.email.EmailComposer;
import com.mojud.shared.email.EmailMessage;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.BusinessProfile;
import com.mojud.tenancy.BusinessRole;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One business's low-stock digest: the "running low" list (emptiest first, at most
 * {@value #ROWS} rows), to managers and owners who have not turned it off. Nothing low, no email.
 */
@Component
class LowStockDigest implements LowStockDigests {

    static final int ROWS = 30;

    private final LowStock lowStock;
    private final MemberMail mail;
    private final EmailComposer composer;
    private final BusinessProfile profiles;
    private final TransactionTemplate transactions;
    private final String webBaseUrl;

    LowStockDigest(LowStock lowStock, MemberMail mail, EmailComposer composer, BusinessProfile profiles,
            PlatformTransactionManager transactionManager, @Value("${mojud.web.base-url}") String webBaseUrl) {
        this.lowStock = lowStock;
        this.mail = mail;
        this.composer = composer;
        this.profiles = profiles;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setReadOnly(true);
        this.webBaseUrl = webBaseUrl;
    }

    @Override
    public int send(UUID businessId) {
        return TenantScope.call(businessId, () -> transactions.execute(status -> {
            List<LowStock.Item> items = lowStock.list();
            if (items.isEmpty()) {
                return 0;
            }
            String shop = profiles.current().name();
            return mail.send(BusinessRole.MANAGER, MemberMail.LOW_STOCK_DIGEST,
                    locale -> email(composer.in(locale), items, shop, webBaseUrl));
        }));
    }

    /** Snapshots in LowStockDigestTests. */
    static EmailMessage email(EmailComposer.Draft draft, List<LowStock.Item> items, String shop, String webBaseUrl) {
        List<List<String>> rows = items.stream().limit(ROWS)
                .map(item -> List.of(item.productName() + (item.label().isEmpty() ? "" : " (" + item.label() + ")"),
                        item.sku(), draft.number(item.available()), draft.number(item.threshold())))
                .toList();
        draft.subject("email.low_stock.subject", items.size(), shop)
                .paragraph("email.low_stock.intro", shop)
                .table(List.of("email.low_stock.product", "email.low_stock.sku", "email.low_stock.left",
                        "email.low_stock.alert_at"), rows);
        if (items.size() > ROWS) {
            draft.paragraph("email.low_stock.more", items.size() - ROWS);
        }
        return draft.action("email.low_stock.action", webBaseUrl + "/products/low-stock")
                .note("email.low_stock.turn_off", webBaseUrl + "/settings/notifications")
                .build();
    }
}
