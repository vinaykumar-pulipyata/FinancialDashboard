package com.learning.spring.financialdashboard.startup;

import com.learning.spring.financialdashboard.model.AppSetting;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.repository.AppSettingRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.TransactionTagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Gives the transactions that were saved before tags existed their tags, once: oldest first, so a phrase used in an early note
 * helps tag the later ones. A flag in app_setting makes sure it never runs again, so a transaction whose tags you removed by hand
 * stays untagged.
 */
@Component
@Order(3)   // after the categories are there
public class TransactionTagBackfill implements ApplicationRunner {

    static final String FLAG = "transaction.tags.backfilled";

    private static final Logger log = LoggerFactory.getLogger(TransactionTagBackfill.class);

    private final TransactionRepository transactions;
    private final TransactionTagService tags;
    private final AppSettingRepository settings;
    private final TransactionTemplate tx;

    public TransactionTagBackfill(TransactionRepository transactions, TransactionTagService tags, AppSettingRepository settings,
                                  TransactionTemplate tx) {
        this.transactions = transactions;
        this.tags = tags;
        this.settings = settings;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        backfill();
    }

    public void backfill() {
        if (settings.findById(FLAG).isPresent()) {
            return;
        }
        int tagged = tx.execute(status -> {
            List<Transaction> all = new ArrayList<>(transactions.findAllByOrderByDateDescIdDesc());
            Collections.reverse(all);   // oldest first
            Map<String, Integer> vocabulary = tags.vocabulary();
            int count = 0;
            for (Transaction t : all) {
                if (!t.getTags().isEmpty()) {
                    continue;
                }
                List<String> found = tags.suggest(t.getNote(), t.getDescription(), t.getCategoryId(), vocabulary);
                if (found.isEmpty()) {
                    continue;
                }
                t.setTags(found);
                found.forEach(tag -> vocabulary.merge(tag, 1, Integer::sum));
                count++;
            }
            transactions.saveAll(all);
            settings.save(new AppSetting(FLAG, "true"));
            return count;
        });
        log.info("Tags were added to {} earlier transactions", tagged);
    }
}
