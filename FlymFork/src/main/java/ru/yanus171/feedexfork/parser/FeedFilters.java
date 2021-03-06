package ru.yanus171.feedexfork.parser;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import ru.yanus171.feedexfork.MainApplication;
import ru.yanus171.feedexfork.provider.FeedData;
import ru.yanus171.feedexfork.utils.DebugApp;
import ru.yanus171.feedexfork.utils.PrefUtils;

import static ru.yanus171.feedexfork.provider.FeedData.FilterColumns.DB_APPLIED_TO_AUTHOR;
import static ru.yanus171.feedexfork.provider.FeedData.FilterColumns.DB_APPLIED_TO_CATEGORY;
import static ru.yanus171.feedexfork.provider.FeedData.FilterColumns.DB_APPLIED_TO_CONTENT;
import static ru.yanus171.feedexfork.provider.FeedData.FilterColumns.DB_APPLIED_TO_TITLE;
import static ru.yanus171.feedexfork.provider.FeedData.FilterColumns.DB_APPLIED_TO_URL;

public class FeedFilters {

    private final ArrayList<Rule> mFilters = new ArrayList<>();

    public FeedFilters(String feedId) {
        init( GetCursor(feedId) );
    }
    public FeedFilters(Cursor c) {
        init( c );
    }
    public void init(Cursor c) {
        if ( c.moveToFirst() )
            do {
                Rule r = new Rule();
                r.filterText = c.getString(0);
                r.isRegex = c.getInt(1) == 1;
                r.mApplyType = c.getInt(2);
                r.isAcceptRule = c.getInt(3) == 1;
                r.isMarkAsStarred = c.getInt(4) == 1;
                r.isRemoveText = c.getInt(5) == 1;
                mFilters.add(r);
            } while ( c.moveToNext() );

        if ( PrefUtils.getBoolean( "global_marks_as_star_filter_on", false ) ) {
            String[] list = TextUtils.split( PrefUtils.getString( "global_marks_as_star_filter_rule",
                                                            "-------||||||-______" ),
                                    "\n"  );
            for ( String rule: list ) {
                if ( rule.trim().isEmpty() )
                    continue;
                Rule r = new Rule();
                r.filterText = rule;
                r.isRegex = PrefUtils.getBoolean("global_marks_as_star_filter_rule_is_regex", false);
                r.mApplyType = PrefUtils.getBoolean("global_marks_as_star_filter_apply_to_title", true) ? DB_APPLIED_TO_TITLE : DB_APPLIED_TO_CONTENT;
                r.isAcceptRule = false;
                r.isMarkAsStarred = true;
                mFilters.add(r);
            }
        }

        {
            String[] list = TextUtils.split(PrefUtils.getString("global_removeText_filter_rule", "-------||||||-______"), "\n");
            for (String rule : list) {
                if (rule.trim().isEmpty())
                    continue;
                Rule r = new Rule();
                r.filterText = rule;
                r.isRegex = true;
                r.mApplyType = DB_APPLIED_TO_CONTENT;
                r.isAcceptRule = false;
                r.isMarkAsStarred = false;
                r.isRemoveText = true;
                mFilters.add(r);
            }
        }

    }

    public static Cursor GetCursor(String feedId) {
        ContentResolver cr = MainApplication.getContext().getContentResolver();
        return cr.query(getCursorUri(feedId), getCursorProjection(), null, null, null);
    }

    @NotNull
    public static String[] getCursorProjection() {
        return new String[]{FeedData.FilterColumns.FILTER_TEXT, FeedData.FilterColumns.IS_REGEX,
                FeedData.FilterColumns.APPLY_TYPE, FeedData.FilterColumns.IS_ACCEPT_RULE, FeedData.FilterColumns.IS_MARK_STARRED, FeedData.FilterColumns.IS_REMOVE_TEXT};
    }

    public static Uri getCursorUri(String feedId) {
        return FeedData.FilterColumns.FILTERS_FOR_FEED_CONTENT_URI(feedId);
    }

    public boolean isEntryFiltered(String title, String author, String url, String content, String[] categoryList) {
        final String categories = categoryList == null ? "" : TextUtils.join( ", ", categoryList );
        boolean isFiltered = false;

        for (Rule r : mFilters) {
            if ( r.isMarkAsStarred || r.isRemoveText )
                continue;

            if ( r.isAcceptRule && r.mApplyType == DB_APPLIED_TO_CATEGORY && categories.isEmpty() )
                continue;

            boolean isMatch = r.isMatch( title, author, url, content, categories );

            if (r.isAcceptRule) {
                if (isMatch) {

                    isFiltered = false;
                    break; // accept rules override reject rules, the rest of the rules must be ignored
                } else {
                    isFiltered = true;
                    //break;
                }
            } else if (isMatch) {
                isFiltered = true;
                //break; // no break, there might be an accept rule later
            }
        }

        return isFiltered;
    }

    public boolean isMarkAsStarred(String title, String author, String url, String content, String[] categoryList) {
        final String categories = categoryList == null ? "" : TextUtils.join( ", ", categoryList );

        for (Rule r : mFilters)
            if ( r.isMarkAsStarred && r.isMatch( title, author, url, content, categories ) )
                return true;
        return false;
    }
    public String removeText( String text, int applyType ) {
        String result = text;
        for (Rule r : mFilters)
            if ( r.isRemoveText && applyType == r.mApplyType) {
                if (r.isRegex)
                    result = result.replaceAll( r.filterText, "" );
                else
                    result = result.replace( r.filterText, "" );
            }
        return result;
    }

    private class Rule {
        String filterText;
        boolean isRegex;
        int mApplyType;
        boolean isAcceptRule;
        boolean isMarkAsStarred = false;
        boolean isRemoveText = false;

        boolean isMatch(String title, String author, String url, String content, String categories) {
            boolean result = false;
            author = author == null ? "" : author;
            title = title == null ? "" : title;
            url = url == null ? "" : url;
            categories = categories == null ? "" : categories;
            content = content == null ? "" : content;

            if (isRegex) {
                try {
                    Pattern p = Pattern.compile(filterText);
                    if ( mApplyType == DB_APPLIED_TO_TITLE)
                        result = p.matcher(title).find();
                    else if ( mApplyType == DB_APPLIED_TO_AUTHOR)
                        result = p.matcher(author).find();
                    else if ( mApplyType == DB_APPLIED_TO_URL)
                        result = p.matcher(url).find();
                    else if ( mApplyType == DB_APPLIED_TO_CATEGORY)
                        result = p.matcher(categories).find();
                    else if ( mApplyType == DB_APPLIED_TO_CONTENT)
                        result = p.matcher(content).find();
                } catch ( PatternSyntaxException e ) {
                    DebugApp.AddErrorToLog( null, e  );
                }
            } else {
                final String filterTextLow = filterText.toLowerCase();
                result =
                    ( mApplyType == DB_APPLIED_TO_TITLE && title.toLowerCase().contains(filterTextLow ) ) ||
                    ( mApplyType == DB_APPLIED_TO_CATEGORY && categories.toLowerCase().contains(filterTextLow ) ) ||
                    ( mApplyType == DB_APPLIED_TO_CONTENT && content.toLowerCase().contains(filterTextLow ) ) ||
                    ( mApplyType == DB_APPLIED_TO_URL && url.toLowerCase().contains(filterTextLow ) ) ||
                    ( mApplyType == DB_APPLIED_TO_AUTHOR && author.toLowerCase().contains(filterTextLow ) );
            }
            return result;
        }
    }
}
