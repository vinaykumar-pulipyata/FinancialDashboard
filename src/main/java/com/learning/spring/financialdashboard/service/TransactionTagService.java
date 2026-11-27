package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tags for transactions. A tag is a short lower-case label (the same form as a diary tag). They are worked out in this order:
 * <ol>
 * <li>from the note and the description: names of places and people written with capitals ("Barbeque Nation"), phrases already used
 * as tags before, and the other meaningful words, the ones used before first; at most {@value #FROM_TEXT};</li>
 * <li>then, while there are fewer than {@value #TARGET} tags, the sub category;</li>
 * <li>then, still under {@value #TARGET}, the category.</li>
 * </ol>
 * Everything is worked out here, from your own transactions: nothing is sent anywhere. A person can still add and remove tags by hand;
 * a transaction saved with a list of tags keeps exactly that list.
 */
@Service
@Transactional
public class TransactionTagService {

    public static final int FROM_TEXT = 3;
    public static final int TARGET = 3;
    public static final int MAX_TAGS = 10;

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");

    /** Words that say nothing about what the money was for. */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "into", "onto", "this", "that", "these", "those", "was", "were", "has", "have", "had",
            "are", "you", "your", "our", "their", "his", "her", "its", "out", "off", "via", "per", "got", "get", "paid", "pay", "payment",
            "paying", "bought", "buy", "purchase", "purchased", "spent", "upi", "inr", "rupees", "rupee", "rs", "amount", "some", "any",
            "etc", "also", "just", "new", "old", "one", "two", "three", "week", "month", "monthly", "weekly", "today", "yesterday",
            "tomorrow", "ke", "ka", "ki", "ko", "se", "me", "hai", "liye",
            "took", "take", "taken", "went", "gone", "going", "did", "done", "does", "made", "make", "gave", "give", "given", "said", "told",
            "came", "come", "been", "being", "will", "would", "could", "should", "need", "needs", "needed", "want", "wanted", "about", "after",
            "before", "while", "then", "than", "them", "they", "there", "here", "when", "what", "which", "who", "how", "why", "not", "but",
            "very", "more", "most", "much", "many", "too", "all", "still", "again", "back", "over", "like", "only");

    private final TransactionRepository transactions;
    private final CategoryRepository categories;

    public TransactionTagService(TransactionRepository transactions, CategoryRepository categories) {
        this.transactions = transactions;
        this.categories = categories;
    }

    /** Every tag in use with the number of transactions that carry it. */
    @Transactional(readOnly = true)
    public Map<String, Integer> vocabulary() {
        Map<String, Integer> uses = new HashMap<>();
        for (Object[] row : transactions.tagCounts()) {
            uses.put((String) row[0], ((Number) row[1]).intValue());
        }
        return uses;
    }

    /** The tags a transaction with this note, description and category would get. */
    @Transactional(readOnly = true)
    public List<String> suggest(String note, String description, Long categoryId) {
        return suggest(note, description, categoryId, vocabulary());
    }

    /** The same, with the vocabulary given (so many transactions can be tagged without asking the database each time). */
    @Transactional(readOnly = true)
    public List<String> suggest(String note, String description, Long categoryId, Map<String, Integer> vocabulary) {
        String text = join(note, description);
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        List<String> fromText = keywords(text, vocabulary, 1);
        for (String tag : fromText) {
            if (tags.size() < FROM_TEXT) {
                tags.add(tag);
            }
        }
        Category category = categoryId == null ? null : categories.findById(categoryId).orElse(null);
        if (category != null) {
            Category parent = category.getParentId() == null ? null : categories.findById(category.getParentId()).orElse(null);
            if (parent != null && tags.size() < TARGET) {
                addSlug(tags, category.getName());
            }
            if (tags.size() < TARGET) {
                addSlug(tags, parent != null ? parent.getName() : category.getName());
            }
        }
        return new ArrayList<>(tags);
    }

    /** Tags a transaction the app writes itself (a Ledger payment, a dividend, a jewellery sale) from its note and category. */
    @Transactional(readOnly = true)
    public void tag(com.learning.spring.financialdashboard.model.Transaction t) {
        t.setTags(suggest(t.getNote(), t.getDescription(), t.getCategoryId()));
    }

    /** Tags typed by a person: each cleaned and checked like a diary tag, duplicates dropped, at most {@value #MAX_TAGS}. */
    public Set<String> clean(Collection<String> raw) {
        Set<String> tags = new LinkedHashSet<>();
        for (String one : raw) {
            if (one != null && !one.isBlank()) {
                try {
                    tags.add(NoteService.cleanTag(one));
                } catch (ValidationException e) {
                    // not the tag itself: tag text is never logged, and the refusal message is
                    throw new ValidationException("A tag is 1 to " + NoteService.MAX_TAG_LENGTH + " characters of letters, numbers, - and _");
                }
            }
        }
        if (tags.size() > MAX_TAGS) {
            throw new ValidationException("A transaction can have at most " + MAX_TAGS + " tags");
        }
        return tags;
    }

    // ------------------------------------------------------------------------------------------ the words

    private static String join(String note, String description) {
        return ((note == null ? "" : note) + " " + (description == null ? "" : description)).strip();
    }

    /**
     * Candidates in order of how well they describe the text: learned phrases, capitalised names, learned words, other words. An
     * "other" word (one never used as a tag) must appear at least {@code minOther} times, so a long text (a diary note) is not tagged
     * with every word in it. Also used for the diary: {@code vocabulary} is then the tags of the notes.
     */
    public List<String> keywords(String text, Map<String, Integer> vocabulary, int minOther) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> raw = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            raw.add(m.group());
        }
        List<String> lower = raw.stream().map(w -> w.toLowerCase(Locale.ROOT)).toList();
        String spaced = " " + String.join(" ", lower) + " ";

        LinkedHashSet<String> learnedPhrases = new LinkedHashSet<>();
        vocabulary.entrySet().stream()
                .filter(e -> e.getKey().contains("-") && spaced.contains(" " + e.getKey().replace('-', ' ') + " "))
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> learnedPhrases.add(e.getKey()));

        LinkedHashSet<String> names = new LinkedHashSet<>();     // runs of two or more capitalised words: "Barbeque Nation"
        List<String> run = new ArrayList<>();
        for (int i = 0; i <= raw.size(); i++) {
            String word = i < raw.size() ? raw.get(i) : null;
            boolean capital = word != null && word.length() >= 2 && Character.isUpperCase(word.codePointAt(0)) && !STOP_WORDS.contains(lower.get(i));
            if (capital) {
                run.add(lower.get(i));
            } else {
                if (run.size() >= 2) {
                    String phrase = slug(String.join("-", run));
                    if (!phrase.isEmpty()) {
                        names.add(phrase);
                    }
                }
                run.clear();
            }
        }

        Set<String> covered = new LinkedHashSet<>();            // words already inside a phrase
        for (String phrase : concat(learnedPhrases, names)) {
            covered.addAll(List.of(phrase.split("-")));
        }
        Map<String, Integer> times = new HashMap<>();
        for (String word : lower) {
            times.merge(word, 1, Integer::sum);
        }
        List<String> learnedWords = new ArrayList<>(), otherWords = new ArrayList<>();
        for (String word : lower) {
            if (word.length() < 3 || word.chars().allMatch(Character::isDigit) || STOP_WORDS.contains(word) || covered.contains(word)) {
                continue;
            }
            if (learnedWords.contains(word) || otherWords.contains(word)) {
                continue;
            }
            if (vocabulary.getOrDefault(word, 0) > 0) {
                learnedWords.add(word);
            } else if (times.get(word) >= minOther) {
                otherWords.add(word);
            }
        }
        learnedWords.sort((a, b) -> vocabulary.get(b) - vocabulary.get(a));

        LinkedHashSet<String> ranked = new LinkedHashSet<>();
        ranked.addAll(learnedPhrases);
        ranked.addAll(names);
        ranked.addAll(learnedWords);
        ranked.addAll(otherWords);
        return new ArrayList<>(ranked);
    }

    private static List<String> concat(Collection<String> a, Collection<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static void addSlug(Set<String> tags, String name) {
        String tag = slug(name);
        if (!tag.isEmpty()) {
            tags.add(tag);
        }
    }

    /** A name as a tag: lower case, anything that is not a letter, number, _ or - turned into a hyphen, at most 30 characters. */
    static String slug(String name) {
        String tag = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}_-]+", "-").replaceAll("-{2,}", "-");
        tag = tag.replaceAll("^-+|-+$", "");
        if (tag.length() > NoteService.MAX_TAG_LENGTH) {
            tag = tag.substring(0, NoteService.MAX_TAG_LENGTH).replaceAll("-+$", "");
        }
        return tag;
    }
}
