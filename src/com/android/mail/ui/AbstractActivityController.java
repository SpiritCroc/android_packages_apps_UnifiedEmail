/*******************************************************************************
 *      Copyright (C) 2012 Google Inc.
 *      Licensed to The Android Open Source Project.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 *******************************************************************************/

package com.android.mail.ui;

import android.app.ActionBar;
import android.app.ActionBar.LayoutParams;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.LoaderManager;
import android.app.SearchManager;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.database.DataSetObservable;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.SearchRecentSuggestions;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import com.android.mail.ConversationListContext;
import com.android.mail.R;
import com.android.mail.browse.ConversationCursor;
import com.android.mail.browse.ConversationItemView;
import com.android.mail.browse.ConversationItemViewModel;
import com.android.mail.browse.ConversationPagerController;
import com.android.mail.browse.ConversationCursor.ConversationOperation;
import com.android.mail.browse.MessageCursor.ConversationMessage;
import com.android.mail.browse.SelectedConversationsActionMenu;
import com.android.mail.browse.SyncErrorDialogFragment;
import com.android.mail.compose.ComposeActivity;
import com.android.mail.providers.Account;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.ConversationInfo;
import com.android.mail.providers.Folder;
import com.android.mail.providers.FolderWatcher;
import com.android.mail.providers.MailAppProvider;
import com.android.mail.providers.Settings;
import com.android.mail.providers.SuggestionsProvider;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.AccountCapabilities;
import com.android.mail.providers.UIProvider.AccountColumns;
import com.android.mail.providers.UIProvider.AccountCursorExtraKeys;
import com.android.mail.providers.UIProvider.AutoAdvance;
import com.android.mail.providers.UIProvider.ConversationColumns;
import com.android.mail.providers.UIProvider.ConversationOperations;
import com.android.mail.providers.UIProvider.FolderCapabilities;
import com.android.mail.ui.ActionableToastBar.ActionClickedListener;
import com.android.mail.utils.ContentProviderTask;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TimerTask;


/**
 * This is an abstract implementation of the Activity Controller. This class
 * knows how to respond to menu items, state changes, layout changes, etc. It
 * weaves together the views and listeners, dispatching actions to the
 * respective underlying classes.
 * <p>
 * Even though this class is abstract, it should provide default implementations
 * for most, if not all the methods in the ActivityController interface. This
 * makes the task of the subclasses easier: OnePaneActivityController and
 * TwoPaneActivityController can be concise when the common functionality is in
 * AbstractActivityController.
 * </p>
 * <p>
 * In the Gmail codebase, this was called BaseActivityController
 * </p>
 */
public abstract class AbstractActivityController implements ActivityController {
    // Keys for serialization of various information in Bundles.
    /** Tag for {@link #mAccount} */
    private static final String SAVED_ACCOUNT = "saved-account";
    /** Tag for {@link #mFolder} */
    private static final String SAVED_FOLDER = "saved-folder";
    /** Tag for {@link #mCurrentConversation} */
    private static final String SAVED_CONVERSATION = "saved-conversation";
    /** Tag for {@link #mSelectedSet} */
    private static final String SAVED_SELECTED_SET = "saved-selected-set";
    /** Tag for {@link ActionableToastBar#getOperation()} */
    private static final String SAVED_TOAST_BAR_OP = "saved-toast-bar-op";
    /** Tag for {@link #mFolderListFolder} */
    private static final String SAVED_HIERARCHICAL_FOLDER = "saved-hierarchical-folder";
    /** Tag for {@link ConversationListContext#searchQuery} */
    private static final String SAVED_QUERY = "saved-query";

    /** Tag  used when loading a wait fragment */
    protected static final String TAG_WAIT = "wait-fragment";
    /** Tag used when loading a conversation list fragment. */
    public static final String TAG_CONVERSATION_LIST = "tag-conversation-list";
    /** Tag used when loading a folder list fragment. */
    protected static final String TAG_FOLDER_LIST = "tag-folder-list";

    protected Account mAccount;
    protected Folder mFolder;
    /** True when {@link #mFolder} is first shown to the user. */
    private boolean mFolderChanged = false;
    protected MailActionBarView mActionBarView;
    protected final ControllableActivity mActivity;
    protected final Context mContext;
    private final FragmentManager mFragmentManager;
    protected final RecentFolderList mRecentFolderList;
    protected ConversationListContext mConvListContext;
    protected Conversation mCurrentConversation;

    /** A {@link android.content.BroadcastReceiver} that suppresses new e-mail notifications. */
    private SuppressNotificationReceiver mNewEmailReceiver = null;

    protected Handler mHandler = new Handler();

    /**
     * The current mode of the application. All changes in mode are initiated by
     * the activity controller. View mode changes are propagated to classes that
     * attach themselves as listeners of view mode changes.
     */
    protected final ViewMode mViewMode;
    protected ContentResolver mResolver;
    protected boolean isLoaderInitialized = false;
    private AsyncRefreshTask mAsyncRefreshTask;

    private boolean mDestroyed;

    /**
     * Are we in a point in the Activity/Fragment lifecycle where it's safe to execute fragment
     * transactions? (including back stack manipulation)
     * <p>
     * Per docs in {@link FragmentManager#beginTransaction()}, this flag starts out true, switches
     * to false after {@link Activity#onSaveInstanceState}, and becomes true again in both onStart
     * and onResume.
     */
    private boolean mSafeToModifyFragments = true;

    private final Set<Uri> mCurrentAccountUris = Sets.newHashSet();
    protected ConversationCursor mConversationListCursor;
    private final DataSetObservable mConversationListObservable = new DataSetObservable() {
        @Override
        public void registerObserver(DataSetObserver observer) {
            final int count = mObservers.size();
            super.registerObserver(observer);
            LogUtils.d(LOG_TAG, "IN AAC.register(List)Observer: %s before=%d after=%d", observer,
                    count, mObservers.size());
        }
        @Override
        public void unregisterObserver(DataSetObserver observer) {
            final int count = mObservers.size();
            super.unregisterObserver(observer);
            LogUtils.d(LOG_TAG, "IN AAC.unregister(List)Observer: %s before=%d after=%d", observer,
                    count, mObservers.size());
        }
    };

    private RefreshTimerTask mConversationListRefreshTask;

    /** Listeners that are interested in changes to the current account. */
    private final DataSetObservable mAccountObservers = new DataSetObservable() {
        @Override
        public void registerObserver(DataSetObserver observer) {
            final int count = mObservers.size();
            super.registerObserver(observer);
            LogUtils.d(LOG_TAG, "IN AAC.register(Account)Observer: %s before=%d after=%d",
                    observer, count, mObservers.size());
        }
        @Override
        public void unregisterObserver(DataSetObserver observer) {
            final int count = mObservers.size();
            super.unregisterObserver(observer);
            LogUtils.d(LOG_TAG, "IN AAC.unregister(Account)Observer: %s before=%d after=%d",
                    observer, count, mObservers.size());
        }
    };

    /** Listeners that are interested in changes to the recent folders. */
    private final DataSetObservable mRecentFolderObservers = new DataSetObservable() {
        @Override
        public void registerObserver(DataSetObserver observer) {
            final int count = mObservers.size();
            super.registerObserver(observer);
            LogUtils.d(LOG_TAG, "IN AAC.register(RecentFolder)Observer: %s before=%d after=%d",
                    observer, count, mObservers.size());
        }
        @Override
        public void unregisterObserver(DataSetObserver observer) {
            final int count = mObservers.size();
            super.unregisterObserver(observer);
            LogUtils.d(LOG_TAG, "IN AAC.unregister(RecentFolder)Observer: %s before=%d after=%d",
                    observer, count, mObservers.size());
        }
    };

    /**
     * Selected conversations, if any.
     */
    private final ConversationSelectionSet mSelectedSet = new ConversationSelectionSet();

    private final int mFolderItemUpdateDelayMs;

    /** Keeps track of selected and unselected conversations */
    final protected ConversationPositionTracker mTracker;

    /**
     * Action menu associated with the selected set.
     */
    SelectedConversationsActionMenu mCabActionMenu;
    protected ActionableToastBar mToastBar;
    protected ConversationPagerController mPagerController;

    // this is split out from the general loader dispatcher because its loader doesn't return a
    // basic Cursor
    private final ConversationListLoaderCallbacks mListCursorCallbacks =
            new ConversationListLoaderCallbacks();

    private final DataSetObservable mFolderObservable = new DataSetObservable();

    protected static final String LOG_TAG = LogTag.getLogTag();
    /** Constants used to differentiate between the types of loaders. */
    private static final int LOADER_ACCOUNT_CURSOR = 0;
    private static final int LOADER_FOLDER_CURSOR = 2;
    private static final int LOADER_RECENT_FOLDERS = 3;
    private static final int LOADER_CONVERSATION_LIST = 4;
    private static final int LOADER_ACCOUNT_INBOX = 5;
    private static final int LOADER_SEARCH = 6;
    private static final int LOADER_ACCOUNT_UPDATE_CURSOR = 7;
    /**
     * Guaranteed to be the last loader ID used by the activity. Loaders are owned by Activity or
     * fragments, and within an activity, loader IDs need to be unique. A hack to ensure that the
     * {@link FolderWatcher} can create its folder loaders without clashing with the IDs of those
     * of the {@link AbstractActivityController}. Currently, the {@link FolderWatcher} is the only
     * other class that uses this activity's LoaderManager. If another class needs activity-level
     * loaders, consider consolidating the loaders in a central location: a UI-less fragment
     * perhaps.
     */
    public static final int LAST_LOADER_ID = 100;

    private static final int ADD_ACCOUNT_REQUEST_CODE = 1;
    private static final int REAUTHENTICATE_REQUEST_CODE = 2;

    /** The pending destructive action to be carried out before swapping the conversation cursor.*/
    private DestructiveAction mPendingDestruction;
    protected AsyncRefreshTask mFolderSyncTask;
    // Task for setting any share intents for the account to enabled.
    // This gets cancelled if the user kills the app before it finishes, and
    // will just run the next time the user opens the app.
    private AsyncTask<String, Void, Void> mEnableShareIntents;
    private Folder mFolderListFolder;
    private boolean mIsDragHappening;
    private int mShowUndoBarDelay;
    private boolean mRecentsDataUpdated;
    /** A wait fragment we added, if any. */
    private WaitFragment mWaitFragment;
    /** True if we have results from a search query */
    private boolean mHaveSearchResults = false;
    public static final String SYNC_ERROR_DIALOG_FRAGMENT_TAG = "SyncErrorDialogFragment";

    public AbstractActivityController(MailActivity activity, ViewMode viewMode) {
        mActivity = activity;
        mFragmentManager = mActivity.getFragmentManager();
        mViewMode = viewMode;
        mContext = activity.getApplicationContext();
        mRecentFolderList = new RecentFolderList(mContext);
        mTracker = new ConversationPositionTracker(this);
        // Allow the fragment to observe changes to its own selection set. No other object is
        // aware of the selected set.
        mSelectedSet.addObserver(this);

        mFolderItemUpdateDelayMs =
                mContext.getResources().getInteger(R.integer.folder_item_refresh_delay_ms);
        mShowUndoBarDelay =
                mContext.getResources().getInteger(R.integer.show_undo_bar_delay_ms);
    }

    @Override
    public Account getCurrentAccount() {
        return mAccount;
    }

    @Override
    public ConversationListContext getCurrentListContext() {
        return mConvListContext;
    }

    @Override
    public String getHelpContext() {
        final int mode = mViewMode.getMode();
        final int helpContextResId;
        switch (mode) {
            case ViewMode.WAITING_FOR_ACCOUNT_INITIALIZATION:
                helpContextResId = R.string.wait_help_context;
                break;
            default:
                helpContextResId = R.string.main_help_context;
        }
        return mContext.getString(helpContextResId);
    }

    @Override
    public final ConversationCursor getConversationListCursor() {
        return mConversationListCursor;
    }

    /**
     * Check if the fragment is attached to an activity and has a root view.
     * @param in
     * @return true if the fragment is valid, false otherwise
     */
    private static final boolean isValidFragment(Fragment in) {
        if (in == null || in.getActivity() == null || in.getView() == null) {
            return false;
        }
        return true;
    }

    /**
     * Get the conversation list fragment for this activity. If the conversation list fragment is
     * not attached, this method returns null.
     *
     * Caution! This method returns the {@link ConversationListFragment} after the fragment has been
     * added, <b>and</b> after the {@link FragmentManager} has run through its queue to add the
     * fragment. There is a non-trivial amount of time after the fragment is instantiated and before
     * this call returns a non-null value, depending on the {@link FragmentManager}. If you
     * need the fragment immediately after adding it, consider making the fragment an observer of
     * the controller and perform the task immediately on {@link Fragment#onActivityCreated(Bundle)}
     */
    protected ConversationListFragment getConversationListFragment() {
        final Fragment fragment = mFragmentManager.findFragmentByTag(TAG_CONVERSATION_LIST);
        if (isValidFragment(fragment)) {
            return (ConversationListFragment) fragment;
        }
        return null;
    }

    /**
     * Returns the folder list fragment attached with this activity. If no such fragment is attached
     * this method returns null.
     *
     * Caution! This method returns the {@link FolderListFragment} after the fragment has been
     * added, <b>and</b> after the {@link FragmentManager} has run through its queue to add the
     * fragment. There is a non-trivial amount of time after the fragment is instantiated and before
     * this call returns a non-null value, depending on the {@link FragmentManager}. If you
     * need the fragment immediately after adding it, consider making the fragment an observer of
     * the controller and perform the task immediately on {@link Fragment#onActivityCreated(Bundle)}
     */
    protected FolderListFragment getFolderListFragment() {
        final Fragment fragment = mFragmentManager.findFragmentByTag(TAG_FOLDER_LIST);
        if (isValidFragment(fragment)) {
            return (FolderListFragment) fragment;
        }
        return null;
    }

    /**
     * Initialize the action bar. This is not visible to OnePaneController and
     * TwoPaneController so they cannot override this behavior.
     */
    private void initializeActionBar() {
        final ActionBar actionBar = mActivity.getActionBar();
        if (actionBar == null) {
            return;
        }

        // be sure to inherit from the ActionBar theme when inflating
        final LayoutInflater inflater = LayoutInflater.from(actionBar.getThemedContext());
        final boolean isSearch = mActivity.getIntent() != null
                && Intent.ACTION_SEARCH.equals(mActivity.getIntent().getAction());
        mActionBarView = (MailActionBarView) inflater.inflate(
                isSearch ? R.layout.search_actionbar_view : R.layout.actionbar_view, null);
        mActionBarView.initialize(mActivity, this, mViewMode, actionBar, mRecentFolderList);
    }

    /**
     * Attach the action bar to the activity.
     */
    private void attachActionBar() {
        final ActionBar actionBar = mActivity.getActionBar();
        if (actionBar != null && mActionBarView != null) {
            actionBar.setCustomView(mActionBarView, new ActionBar.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            // Show a custom view and home icon, but remove the title
            final int mask = ActionBar.DISPLAY_SHOW_CUSTOM | ActionBar.DISPLAY_SHOW_TITLE
                    | ActionBar.DISPLAY_SHOW_HOME;
            final int enabled = ActionBar.DISPLAY_SHOW_CUSTOM | ActionBar.DISPLAY_SHOW_HOME;
            actionBar.setDisplayOptions(enabled, mask);
            mActionBarView.attach();
        }
        mViewMode.addListener(mActionBarView);
    }

    /**
     * Returns whether the conversation list fragment is visible or not.
     * Different layouts will have their own notion on the visibility of
     * fragments, so this method needs to be overriden.
     *
     */
    protected abstract boolean isConversationListVisible();

    /**
     * If required, starts wait mode for the current account.
     */
    final void perhapsEnterWaitMode() {
        // If the account is not initialized, then show the wait fragment, since nothing can be
        // shown.
        if (mAccount.isAccountInitializationRequired()) {
            showWaitForInitialization();
            return;
        }

        final boolean inWaitingMode = inWaitMode();
        final boolean isSyncRequired = mAccount.isAccountSyncRequired();
        if (isSyncRequired) {
            if (inWaitingMode) {
                // Update the WaitFragment's account object
                updateWaitMode();
            } else {
                // Transition to waiting mode
                showWaitForInitialization();
            }
        } else if (inWaitingMode) {
            // Dismiss waiting mode
            hideWaitForInitialization();
        }
    }

    @Override
    public void onAccountChanged(Account account) {
        // Is the account or account settings different from the existing account?
        final boolean firstLoad = mAccount == null;
        final boolean accountChanged = firstLoad || !account.uri.equals(mAccount.uri);
        // If nothing has changed, return early without wasting any more time.
        if (!accountChanged && !account.settingsDiffer(mAccount)) {
            return;
        }
        // We also don't want to do anything if the new account is null
        if (account == null) {
            LogUtils.e(LOG_TAG, "AAC.onAccountChanged(null) called.");
            return;
        }
        final String accountName = account.name;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                MailActivity.setForegroundNdef(MailActivity.getMailtoNdef(accountName));
            }
        });
        if (accountChanged) {
            commitDestructiveActions(false);
        }
        // Change the account here
        setAccount(account);
        // And carry out associated actions.
        cancelRefreshTask();
        if (accountChanged) {
            loadAccountInbox();
        }
        // Check if we need to force setting up an account before proceeding.
        if (mAccount != null && !Uri.EMPTY.equals(mAccount.settings.setupIntentUri)) {
            // Launch the intent!
            final Intent intent = new Intent(Intent.ACTION_EDIT);
            intent.setData(mAccount.settings.setupIntentUri);
            mActivity.startActivity(intent);
        }
    }

    /**
     * Adds a listener interested in change in the current account. If a class is storing a
     * reference to the current account, it should listen on changes, so it can receive updates to
     * settings. Must happen in the UI thread.
     */
    @Override
    public void registerAccountObserver(DataSetObserver obs) {
        mAccountObservers.registerObserver(obs);
    }

    /**
     * Removes a listener from receiving current account changes.
     * Must happen in the UI thread.
     */
    @Override
    public void unregisterAccountObserver(DataSetObserver obs) {
        mAccountObservers.unregisterObserver(obs);
    }

    @Override
    public Account getAccount() {
        return mAccount;
    }

    private void fetchSearchFolder(Intent intent) {
        final Bundle args = new Bundle();
        args.putString(ConversationListContext.EXTRA_SEARCH_QUERY, intent
                .getStringExtra(ConversationListContext.EXTRA_SEARCH_QUERY));
        mActivity.getLoaderManager().restartLoader(LOADER_SEARCH, args, this);
    }

    @Override
    public void onFolderChanged(Folder folder) {
        changeFolder(folder, null);
    }

    /**
     * Sets the folder state without changing view mode and without creating a list fragment, if
     * possible.
     * @param folder
     */
    private void setListContext(Folder folder, String query) {
        updateFolder(folder);
        if (query != null) {
            mConvListContext = ConversationListContext.forSearchQuery(mAccount, mFolder, query);
        } else {
            mConvListContext = ConversationListContext.forFolder(mAccount, mFolder);
        }
        cancelRefreshTask();
    }

    /**
     * Changes the folder to the value provided here. This causes the view mode to change.
     * @param folder the folder to change to
     * @param query if non-null, this represents the search string that the folder represents.
     */
    private final void changeFolder(Folder folder, String query) {
        if (!Objects.equal(mFolder, folder)) {
            commitDestructiveActions(false);
        }
        if (folder != null && !folder.equals(mFolder)
                || (mViewMode.getMode() != ViewMode.CONVERSATION_LIST)) {
            setListContext(folder, query);
            showConversationList(mConvListContext);
        }
        resetActionBarIcon();
    }

    @Override
    public void onFolderSelected(Folder folder) {
        onFolderChanged(folder);
    }

    /**
     * Update the recent folders. This only needs to be done once when accessing a new folder.
     */
    private void updateRecentFolderList() {
        if (mFolder != null) {
            mRecentFolderList.touchFolder(mFolder, mAccount);
        }
    }

    /**
     * Adds a listener interested in change in the recent folders. If a class is storing a
     * reference to the recent folders, it should listen on changes, so it can receive updates.
     * Must happen in the UI thread.
     */
    @Override
    public void registerRecentFolderObserver(DataSetObserver obs) {
        mRecentFolderObservers.registerObserver(obs);
    }

    /**
     * Removes a listener from receiving recent folder changes.
     * Must happen in the UI thread.
     */
    @Override
    public void unregisterRecentFolderObserver(DataSetObserver obs) {
        mRecentFolderObservers.unregisterObserver(obs);
    }

    @Override
    public RecentFolderList getRecentFolders() {
        return mRecentFolderList;
    }

    // TODO(mindyp): set this up to store a copy of the folder as a transient
    // field in the account.
    @Override
    public void loadAccountInbox() {
        restartOptionalLoader(LOADER_ACCOUNT_INBOX);
    }

    /**
     * Marks the {@link #mFolderChanged} value if the newFolder is different from the existing
     * {@link #mFolder}. This should be called immediately <b>before</b> assigning newFolder to
     * mFolder.
     * @param newFolder
     */
    private final void setHasFolderChanged(final Folder newFolder) {
        // We should never try to assign a null folder. But in the rare event that we do, we should
        // only set the bit when we have a valid folder, and null is not valid.
        if (newFolder == null) {
            return;
        }
        // If the previous folder was null, or if the two folders represent different data, then we
        // consider that the folder has changed.
        if (mFolder == null || !newFolder.uri.equals(mFolder.uri)) {
            mFolderChanged = true;
        }
    }

    /**
     * Sets the current folder if it is different from the object provided here. This method does
     * NOT notify the folder observers that a change has happened. Observers are notified when we
     * get an updated folder from the loaders, which will happen as a consequence of this method
     * (since this method starts/restarts the loaders).
     * @param folder The folder to assign
     */
    private void updateFolder(Folder folder) {
        if (folder == null || !folder.isInitialized()) {
            LogUtils.e(LOG_TAG, new Error(), "AAC.setFolder(%s): Bad input", folder);
            return;
        }
        if (folder.equals(mFolder)) {
            LogUtils.d(LOG_TAG, "AAC.setFolder(%s): Input matches mFolder", folder);
            return;
        }
        final boolean wasNull = mFolder == null;
        LogUtils.d(LOG_TAG, "AbstractActivityController.setFolder(%s)", folder.name);
        final LoaderManager lm = mActivity.getLoaderManager();
        // updateFolder is called from AAC.onLoadFinished() on folder changes.  We need to
        // ensure that the folder is different from the previous folder before marking the
        // folder changed.
        setHasFolderChanged(folder);
        mFolder = folder;

        // We do not need to notify folder observers yet. Instead we start the loaders and
        // when the load finishes, we will get an updated folder. Then, we notify the
        // folderObservers in onLoadFinished.
        mActionBarView.setFolder(mFolder);

        // Only when we switch from one folder to another do we want to restart the
        // folder and conversation list loaders (to trigger onCreateLoader).
        // The first time this runs when the activity is [re-]initialized, we want to re-use the
        // previous loader's instance and data upon configuration change (e.g. rotation).
        // If there was not already an instance of the loader, init it.
        if (lm.getLoader(LOADER_FOLDER_CURSOR) == null) {
            lm.initLoader(LOADER_FOLDER_CURSOR, null, this);
        } else {
            lm.restartLoader(LOADER_FOLDER_CURSOR, null, this);
        }
        // In this case, we are starting from no folder, which would occur
        // the first time the app was launched or on orientation changes.
        // We want to attach to an existing loader, if available.
        if (wasNull || lm.getLoader(LOADER_CONVERSATION_LIST) == null) {
            lm.initLoader(LOADER_CONVERSATION_LIST, null, mListCursorCallbacks);
        } else {
            // However, if there was an existing folder AND we have changed
            // folders, we want to restart the loader to get the information
            // for the newly selected folder
            lm.destroyLoader(LOADER_CONVERSATION_LIST);
            lm.initLoader(LOADER_CONVERSATION_LIST, null, mListCursorCallbacks);
        }
    }

    @Override
    public Folder getFolder() {
        return mFolder;
    }

    @Override
    public Folder getHierarchyFolder() {
        return mFolderListFolder;
    }

    @Override
    public void setHierarchyFolder(Folder folder) {
        mFolderListFolder = folder;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case ADD_ACCOUNT_REQUEST_CODE:
                // We were waiting for the user to create an account
                if (resultCode == Activity.RESULT_OK) {
                    // restart the loader to get the updated list of accounts
                    mActivity.getLoaderManager().initLoader(
                            LOADER_ACCOUNT_CURSOR, null, this);
                } else {
                    // The user failed to create an account, just exit the app
                    mActivity.finish();
                }
                break;
            case REAUTHENTICATE_REQUEST_CODE:
                if (resultCode == Activity.RESULT_OK) {
                    // The user successfully authenticated, attempt to refresh the list
                    final Uri refreshUri = mFolder != null ? mFolder.refreshUri : null;
                    if (refreshUri != null) {
                        startAsyncRefreshTask(refreshUri);
                    }
                }
                break;
        }
    }

    /**
     * Inform the conversation cursor that there has been a visibility change.
     * @param visible
     */
    protected synchronized void informCursorVisiblity(boolean visible) {
        if (mConversationListCursor != null) {
            Utils.setConversationCursorVisibility(mConversationListCursor, visible, mFolderChanged);
            // We have informed the cursor. Subsequent visibility changes should not tell it that
            // the folder has changed.
            mFolderChanged = false;
        }
    }

    @Override
    public void onConversationListVisibilityChanged(boolean visible) {
        informCursorVisiblity(visible);
    }

    /**
     * Called when a conversation is visible. Child classes must call the super class implementation
     * before performing local computation.
     */
    @Override
    public void onConversationVisibilityChanged(boolean visible) {
    }

    @Override
    public boolean onCreate(Bundle savedState) {
        initializeActionBar();
        // Allow shortcut keys to function for the ActionBar and menus.
        mActivity.setDefaultKeyMode(Activity.DEFAULT_KEYS_SHORTCUT);
        mResolver = mActivity.getContentResolver();
        mNewEmailReceiver = new SuppressNotificationReceiver();
        mRecentFolderList.initialize(mActivity);

        // All the individual UI components listen for ViewMode changes. This
        // simplifies the amount of logic in the AbstractActivityController, but increases the
        // possibility of timing-related bugs.
        mViewMode.addListener(this);
        mPagerController = new ConversationPagerController(mActivity, this);
        mToastBar = (ActionableToastBar) mActivity.findViewById(R.id.toast_bar);
        attachActionBar();
        FolderSelectionDialog.setDialogDismissed();

        final Intent intent = mActivity.getIntent();
        // Immediately handle a clean launch with intent, and any state restoration
        // that does not rely on restored fragments or loader data
        // any state restoration that relies on those can be done later in
        // onRestoreInstanceState, once fragments are up and loader data is re-delivered
        if (savedState != null) {
            if (savedState.containsKey(SAVED_ACCOUNT)) {
                setAccount((Account) savedState.getParcelable(SAVED_ACCOUNT));
            }
            if (savedState.containsKey(SAVED_FOLDER)) {
                final Folder folder = savedState.getParcelable(SAVED_FOLDER);
                final String query = savedState.getString(SAVED_QUERY, null);
                setListContext(folder, query);
            }
            mViewMode.handleRestore(savedState);
        } else if (intent != null) {
            handleIntent(intent);
        }
        // Create the accounts loader; this loads the account switch spinner.
        mActivity.getLoaderManager().initLoader(LOADER_ACCOUNT_CURSOR, null, this);
        return true;
    }

    @Override
    public void onStart() {
        mSafeToModifyFragments = true;
    }

    @Override
    public void onRestart() {
        DialogFragment fragment = (DialogFragment)
                mFragmentManager.findFragmentByTag(SYNC_ERROR_DIALOG_FRAGMENT_TAG);
        if (fragment != null) {
            fragment.dismiss();
        }
        // When the user places the app in the background by pressing "home",
        // dismiss the toast bar. However, since there is no way to determine if
        // home was pressed, just dismiss any existing toast bar when restarting
        // the app.
        if (mToastBar != null) {
            mToastBar.hide(false);
        }
    }

    @Override
    public Dialog onCreateDialog(int id, Bundle bundle) {
        return null;
    }

    @Override
    public final boolean onCreateOptionsMenu(Menu menu) {
        final MenuInflater inflater = mActivity.getMenuInflater();
        inflater.inflate(mActionBarView.getOptionsMenuId(), menu);
        mActionBarView.onCreateOptionsMenu(menu);
        return true;
    }

    @Override
    public final boolean onKeyDown(int keyCode, KeyEvent event) {
        // TODO(viki): Auto-generated method stub
        return false;
    }

    public abstract boolean doesActionChangeConversationListVisibility(int action);

    @Override
    public final boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        LogUtils.d(LOG_TAG, "AbstractController.onOptionsItemSelected(%d) called.", id);
        boolean handled = true;
        final Collection<Conversation> target = Conversation.listOf(mCurrentConversation);
        final Settings settings = (mAccount == null) ? null : mAccount.settings;
        // The user is choosing a new action; commit whatever they had been
        // doing before. Don't animate if we are launching a new screen.
        commitDestructiveActions(!doesActionChangeConversationListVisibility(id));
        switch (id) {
            case R.id.archive: {
                final boolean showDialog = (settings != null && settings.confirmArchive);
                confirmAndDelete(target, showDialog, R.plurals.confirm_archive_conversation,
                        getDeferredAction(R.id.archive, target, false));
                break;
            }
            case R.id.remove_folder:
                delete(R.id.remove_folder, target,
                        getDeferredRemoveFolder(target, mFolder, true, false, true));
                break;
            case R.id.delete: {
                final boolean showDialog = (settings != null && settings.confirmDelete);
                confirmAndDelete(target, showDialog, R.plurals.confirm_delete_conversation,
                        getDeferredAction(R.id.delete, target, false));
                break;
            }
            case R.id.discard_drafts: {
                final boolean showDialog = (settings != null && settings.confirmDelete);
                confirmAndDelete(target, showDialog, R.plurals.confirm_discard_drafts_conversation,
                        getDeferredAction(R.id.discard_drafts, target, false));
                break;
            }
            case R.id.mark_important:
                updateConversation(Conversation.listOf(mCurrentConversation),
                        ConversationColumns.PRIORITY, UIProvider.ConversationPriority.HIGH);
                break;
            case R.id.mark_not_important:
                if (mFolder != null && mFolder.isImportantOnly()) {
                    delete(R.id.mark_not_important, target,
                            getDeferredAction(R.id.mark_not_important, target, false));
                } else {
                    updateConversation(Conversation.listOf(mCurrentConversation),
                            ConversationColumns.PRIORITY, UIProvider.ConversationPriority.LOW);
                }
                break;
            case R.id.mute:
                delete(R.id.mute, target, getDeferredAction(R.id.mute, target, false));
                break;
            case R.id.report_spam:
                delete(R.id.report_spam, target,
                        getDeferredAction(R.id.report_spam, target, false));
                break;
            case R.id.mark_not_spam:
                // Currently, since spam messages are only shown in list with
                // other spam messages,
                // marking a message not as spam is a destructive action
                delete(R.id.mark_not_spam, target,
                        getDeferredAction(R.id.mark_not_spam, target, false));
                break;
            case R.id.report_phishing:
                delete(R.id.report_phishing, target,
                        getDeferredAction(R.id.report_phishing, target, false));
                break;
            case android.R.id.home:
                onUpPressed();
                break;
            case R.id.compose:
                ComposeActivity.compose(mActivity.getActivityContext(), mAccount);
                break;
            case R.id.show_all_folders:
                showFolderList();
                break;
            case R.id.refresh:
                requestFolderRefresh();
                break;
            case R.id.settings:
                Utils.showSettings(mActivity.getActivityContext(), mAccount);
                break;
            case R.id.folder_options:
                Utils.showFolderSettings(mActivity.getActivityContext(), mAccount, mFolder);
                break;
            case R.id.help_info_menu_item:
                Utils.showHelp(mActivity.getActivityContext(), mAccount, getHelpContext());
                break;
            case R.id.feedback_menu_item:
                Utils.sendFeedback(mActivity.getActivityContext(), mAccount, false);
                break;
            case R.id.manage_folders_item:
                Utils.showManageFolder(mActivity.getActivityContext(), mAccount);
                break;
            case R.id.change_folder:
                final FolderSelectionDialog dialog = FolderSelectionDialog.getInstance(
                        mActivity.getActivityContext(), mAccount, this,
                        Conversation.listOf(mCurrentConversation), false, mFolder);
                if (dialog != null) {
                    dialog.show();
                }
                break;
            default:
                handled = false;
                break;
        }
        return handled;
    }

    @Override
    public void updateConversation(Collection<Conversation> target, ContentValues values) {
        mConversationListCursor.updateValues(mContext, target, values);
        refreshConversationList();
    }

    @Override
    public void updateConversation(Collection <Conversation> target, String columnName,
            boolean value) {
        mConversationListCursor.updateBoolean(mContext, target, columnName, value);
        refreshConversationList();
    }

    @Override
    public void updateConversation(Collection <Conversation> target, String columnName,
            int value) {
        mConversationListCursor.updateInt(mContext, target, columnName, value);
        refreshConversationList();
    }

    @Override
    public void updateConversation(Collection <Conversation> target, String columnName,
            String value) {
        mConversationListCursor.updateString(mContext, target, columnName, value);
        refreshConversationList();
    }

    @Override
    public void markConversationMessagesUnread(Conversation conv, Set<Uri> unreadMessageUris,
            String originalConversationInfo) {
        // The only caller of this method is the conversation view, from where marking unread should
        // *always* take you back to list mode.
        showConversation(null);

        // locally mark conversation unread (the provider is supposed to propagate message unread
        // to conversation unread)
        conv.read = false;

        if (mConversationListCursor == null) {
            LogUtils.e(LOG_TAG, "null ConversationCursor in markConversationMessagesUnread");
            return;
        }

        // only do a granular 'mark unread' if a subset of messages are unread
        final int unreadCount = (unreadMessageUris == null) ? 0 : unreadMessageUris.size();
        final int numMessages = conv.getNumMessages();
        final boolean subsetIsUnread = (numMessages > 1 && unreadCount > 0
                && unreadCount < numMessages);

        if (!subsetIsUnread) {
            // Conversations are neither marked read, nor viewed, and we don't want to show
            // the next conversation.
            markConversationsRead(Collections.singletonList(conv), false, false, false);
        } else {
            mConversationListCursor.setConversationColumn(conv.uri, ConversationColumns.READ, 0);

            // locally update conversation's conversationInfo to revert to original version
            if (originalConversationInfo != null) {
                mConversationListCursor.setConversationColumn(conv.uri,
                        ConversationColumns.CONVERSATION_INFO, originalConversationInfo);
            }

            // applyBatch with each CPO as an UPDATE op on each affected message uri
            final ArrayList<ContentProviderOperation> ops = Lists.newArrayList();
            String authority = null;
            for (Uri messageUri : unreadMessageUris) {
                if (authority == null) {
                    authority = messageUri.getAuthority();
                }
                ops.add(ContentProviderOperation.newUpdate(messageUri)
                        .withValue(UIProvider.MessageColumns.READ, 0)
                        .build());
            }

            new ContentProviderTask() {
                @Override
                protected void onPostExecute(Result result) {
                    // TODO: handle errors?
                }
            }.run(mResolver, authority, ops);
        }
    }

    @Override
    public void markConversationsRead(Collection<Conversation> targets, boolean read,
            boolean viewed) {
        // We want to show the next conversation if we are marking unread.
        markConversationsRead(targets, read, viewed, true);
    }

    private void markConversationsRead(final Collection<Conversation> targets, final boolean read,
            final boolean markViewed, final boolean showNext) {
        // Auto-advance if requested and the current conversation is being marked unread
        if (showNext && !read) {
            final Runnable operation = new Runnable() {
                @Override
                public void run() {
                    markConversationsRead(targets, read, markViewed, showNext);
                }
            };

            if (!showNextConversation(targets, operation)) {
                // This method will be called again if the user selects an autoadvance option
                return;
            }
        }

        final int size = targets.size();
        final List<ConversationOperation> opList = new ArrayList<ConversationOperation>(size);
        for (final Conversation target : targets) {
            final ContentValues value = new ContentValues();
            value.put(ConversationColumns.READ, read);

            // The mark read/unread/viewed operations do not show an undo bar
            value.put(ConversationOperations.Parameters.SUPPRESS_UNDO, true);
            if (markViewed) {
                value.put(ConversationColumns.VIEWED, true);
            }
            final ConversationInfo info = target.conversationInfo;
            if (info != null) {
                boolean changed = info.markRead(read);
                if (changed) {
                    value.put(ConversationColumns.CONVERSATION_INFO,
                            ConversationInfo.toString(info));
                }
            }
            opList.add(mConversationListCursor.getOperationForConversation(
                    target, ConversationOperation.UPDATE, value));
            // Update the local conversation objects so they immediately change state.
            target.read = read;
            if (markViewed) {
                target.markViewed();
            }
        }
        mConversationListCursor.updateBulkValues(mContext, opList);
    }

    /**
     * Auto-advance to a different conversation if the currently visible conversation in
     * conversation mode is affected (deleted, marked unread, etc.).
     *
     * <p>Does nothing if outside of conversation mode.</p>
     *
     * @param target the set of conversations being deleted/marked unread
     */
    @Override
    public void showNextConversation(final Collection<Conversation> target) {
        showNextConversation(target, null);
    }

    /**
     * Auto-advance to a different conversation if the currently visible conversation in
     * conversation mode is affected (deleted, marked unread, etc.).
     *
     * <p>Does nothing if outside of conversation mode.</p>
     *
     * @param target the set of conversations being deleted/marked unread
     * @return <code>false</code> if we aborted because the user has not yet specified a default
     *         action, <code>true</code> otherwise
     */
    private boolean showNextConversation(final Collection<Conversation> target,
            final Runnable operation) {
        final int viewMode = mViewMode.getMode();
        final boolean currentConversationInView = (viewMode == ViewMode.CONVERSATION
                || viewMode == ViewMode.SEARCH_RESULTS_CONVERSATION)
                && Conversation.contains(target, mCurrentConversation);

        if (currentConversationInView) {
            final int autoAdvanceSetting = mAccount.settings.getAutoAdvanceSetting();

            if (autoAdvanceSetting == AutoAdvance.UNSET && Utils.useTabletUI(mContext)) {
                displayAutoAdvanceDialogAndPerformAction(operation);
                return false;
            } else {
                // If we don't have one set, but we're here, just take the default
                final int autoAdvance = (autoAdvanceSetting == AutoAdvance.UNSET) ? Settings
                        .getAutoAdvanceSetting(null)
                        : autoAdvanceSetting;

                final Conversation next = mTracker.getNextConversation(autoAdvance, target);
                LogUtils.d(LOG_TAG, "showNextConversation: showing %s next.", next);
                showConversation(next);
                return true;
            }
        }

        return true;
    }

    /**
     * Displays a the auto-advance dialog, and when the user makes a selection, the preference is
     * stored, and the specified operation is run.
     */
    private void displayAutoAdvanceDialogAndPerformAction(final Runnable operation) {
        final String[] autoAdvanceDisplayOptions =
                mContext.getResources().getStringArray(R.array.prefEntries_autoAdvance);
        final String[] autoAdvanceOptionValues =
                mContext.getResources().getStringArray(R.array.prefValues_autoAdvance);

        final String defaultValue = mContext.getString(R.string.prefDefault_autoAdvance);
        int initialIndex = 0;
        for (int i = 0; i < autoAdvanceOptionValues.length; i++) {
            if (defaultValue.equals(autoAdvanceOptionValues[i])) {
                initialIndex = i;
                break;
            }
        }

        final DialogInterface.OnClickListener listClickListener =
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int whichItem) {
                        final String autoAdvanceValue = autoAdvanceOptionValues[whichItem];
                        final int autoAdvanceValueInt =
                                UIProvider.AutoAdvance.getAutoAdvanceInt(autoAdvanceValue);
                        mAccount.settings.setAutoAdvanceSetting(autoAdvanceValueInt);

                        // Save the user's setting
                        final ContentValues values = new ContentValues(1);
                        values.put(AccountColumns.SettingsColumns.AUTO_ADVANCE, autoAdvanceValue);

                        final ContentResolver resolver = mContext.getContentResolver();
                        resolver.update(mAccount.updateSettingsUri, values, null, null);

                        // Dismiss the dialog, as clicking the items in the list doesn't close the
                        // dialog.
                        dialog.dismiss();
                        if (operation != null) {
                            operation.run();
                        }
                    }
                };

        new AlertDialog.Builder(mActivity.getActivityContext()).setTitle(
                R.string.auto_advance_help_title)
                .setSingleChoiceItems(autoAdvanceDisplayOptions, initialIndex, listClickListener)
                .setPositiveButton(null, null)
                .create()
                .show();
    }

    @Override
    public void starMessage(ConversationMessage msg, boolean starred) {
        if (msg.starred == starred) {
            return;
        }

        msg.starred = starred;

        // locally propagate the change to the owning conversation
        // (figure the provider will properly propagate the change when it commits it)
        //
        // when unstarring, only propagate the change if this was the only message starred
        final boolean conversationStarred = starred || msg.isConversationStarred();
        final Conversation conv = msg.getConversation();
        if (conversationStarred != conv.starred) {
            conv.starred = conversationStarred;
            mConversationListCursor.setConversationColumn(conv.uri,
                    ConversationColumns.STARRED, conversationStarred);
        }

        final ContentValues values = new ContentValues(1);
        values.put(UIProvider.MessageColumns.STARRED, starred ? 1 : 0);

        new ContentProviderTask.UpdateTask() {
            @Override
            protected void onPostExecute(Result result) {
                // TODO: handle errors?
            }
        }.run(mResolver, msg.uri, values, null /* selection*/, null /* selectionArgs */);
    }

    private void requestFolderRefresh() {
        if (mFolder != null) {
            if (mAsyncRefreshTask != null) {
                mAsyncRefreshTask.cancel(true);
            }
            mAsyncRefreshTask = new AsyncRefreshTask(mContext, mFolder.refreshUri);
            mAsyncRefreshTask.execute();
        }
    }

    /**
     * Confirm (based on user's settings) and delete a conversation from the conversation list and
     * from the database.
     * @param target the conversations to act upon
     * @param showDialog true if a confirmation dialog is to be shown, false otherwise.
     * @param confirmResource the resource ID of the string that is shown in the confirmation dialog
     * @param action the action to perform after animating the deletion of the conversations.
     */
    protected void confirmAndDelete(final Collection<Conversation> target, boolean showDialog,
            int confirmResource, final DestructiveAction action) {
        if (showDialog) {
            final AlertDialog.OnClickListener onClick = new AlertDialog.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == DialogInterface.BUTTON_POSITIVE) {
                        delete(0, target, action);
                    }
                }
            };
            final CharSequence message = Utils.formatPlural(mContext, confirmResource,
                    target.size());
            new AlertDialog.Builder(mActivity.getActivityContext()).setMessage(message)
                    .setPositiveButton(R.string.ok, onClick)
                    .setNegativeButton(R.string.cancel, null)
                    .create().show();
        } else {
            delete(0, target, action);
        }
    }

    @Override
    public void delete(final int actionId, final Collection<Conversation> target,
            final Collection<ConversationItemView> targetViews, final DestructiveAction action) {
        // Order of events is critical! The Conversation View Fragment must be
        // notified of the next conversation with showConversation(next) *before* the
        // conversation list
        // fragment has a chance to delete the conversation, animating it away.

        // Update the conversation fragment if the current conversation is
        // deleted.
        final Runnable operation = new Runnable() {
            @Override
            public void run() {
                delete(actionId, target, targetViews, action);
            }
        };

        if (!showNextConversation(target, operation)) {
            // This method will be called again if the user selects an autoadvance option
            return;
        }

        // The conversation list deletes and performs the action if it exists.
        final ConversationListFragment convListFragment = getConversationListFragment();
        if (convListFragment != null) {
            LogUtils.d(LOG_TAG, "AAC.requestDelete: ListFragment is handling delete.");
            convListFragment.requestDelete(actionId, target, targetViews, action);
            return;
        }
        // No visible UI element handled it on our behalf. Perform the action
        // ourself.
        action.performAction();
    }

    @Override
    public void delete(int actionId, final Collection<Conversation> target,
            final DestructiveAction action) {
        delete(actionId, target, null, action);
    }

    /**
     * Requests that the action be performed and the UI state is updated to reflect the new change.
     * @param target
     * @param action
     */
    private void requestUpdate(final Collection<Conversation> target,
            final DestructiveAction action) {
        action.performAction();
        refreshConversationList();
    }

    @Override
    public void onPrepareDialog(int id, Dialog dialog, Bundle bundle) {
        // TODO(viki): Auto-generated method stub
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        return mActionBarView.onPrepareOptionsMenu(menu);
    }

    @Override
    public void onPause() {
        isLoaderInitialized = false;
        enableNotifications();
    }

    @Override
    public void onResume() {
        // Register the receiver that will prevent the status receiver from
        // displaying its notification icon as long as we're running.
        // The SupressNotificationReceiver will block the broadcast if we're looking at the folder
        // that the notification was received for.
        disableNotifications();

        mSafeToModifyFragments = true;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        mViewMode.handleSaveInstanceState(outState);
        if (mAccount != null) {
            LogUtils.d(LOG_TAG, "Saving the account now");
            outState.putParcelable(SAVED_ACCOUNT, mAccount);
        }
        if (mFolder != null) {
            outState.putParcelable(SAVED_FOLDER, mFolder);
        }
        // If this is a search activity, let's store the search query term as well.
        if (ConversationListContext.isSearchResult(mConvListContext)) {
            outState.putString(SAVED_QUERY, mConvListContext.searchQuery);
        }
        if (mCurrentConversation != null && mViewMode.isConversationMode()) {
            outState.putParcelable(SAVED_CONVERSATION, mCurrentConversation);
        }
        if (!mSelectedSet.isEmpty()) {
            outState.putParcelable(SAVED_SELECTED_SET, mSelectedSet);
        }
        if (mToastBar.getVisibility() == View.VISIBLE) {
            outState.putParcelable(SAVED_TOAST_BAR_OP, mToastBar.getOperation());
        }
        final ConversationListFragment convListFragment = getConversationListFragment();
        if (convListFragment != null) {
            convListFragment.getAnimatedAdapter().onSaveInstanceState(outState);
        }
        mSafeToModifyFragments = false;
        outState.putString(SAVED_HIERARCHICAL_FOLDER,
                (mFolderListFolder != null) ? Folder.toString(mFolderListFolder) : null);
    }

    /**
     * @see #mSafeToModifyFragments
     */
    protected boolean safeToModifyFragments() {
        return mSafeToModifyFragments;
    }

    @Override
    public void onSearchRequested(String query) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_SEARCH);
        intent.putExtra(ConversationListContext.EXTRA_SEARCH_QUERY, query);
        intent.putExtra(Utils.EXTRA_ACCOUNT, mAccount);
        intent.setComponent(mActivity.getComponentName());
        mActionBarView.collapseSearch();
        mActivity.startActivity(intent);
    }

    @Override
    public void onStop() {
        if (mEnableShareIntents != null) {
            mEnableShareIntents.cancel(true);
        }
    }

    @Override
    public void onDestroy() {
        // unregister the ViewPager's observer on the conversation cursor
        mPagerController.onDestroy();
        mActionBarView.onDestroy();
        mRecentFolderList.destroy();
        mDestroyed = true;
    }

    /**
     * Set the Action Bar icon according to the mode. The Action Bar icon can contain a back button
     * or not. The individual controller is responsible for changing the icon based on the mode.
     */
    protected abstract void resetActionBarIcon();

    /**
     * {@inheritDoc} Subclasses must override this to listen to mode changes
     * from the ViewMode. Subclasses <b>must</b> call the parent's
     * onViewModeChanged since the parent will handle common state changes.
     */
    @Override
    public void onViewModeChanged(int newMode) {
        // When we step away from the conversation mode, we don't have a current conversation
        // anymore. Let's blank it out so clients calling getCurrentConversation are not misled.
        if (!ViewMode.isConversationMode(newMode)) {
            setCurrentConversation(null);
        }
        // If the viewmode is not set, preserve existing icon.
        if (newMode != ViewMode.UNKNOWN) {
            resetActionBarIcon();
        }
    }

    public void disablePagerUpdates() {
        mPagerController.stopListening();
    }

    public boolean isDestroyed() {
        return mDestroyed;
    }

    @Override
    public void commitDestructiveActions(boolean animate) {
        ConversationListFragment fragment = getConversationListFragment();
        if (fragment != null) {
            fragment.commitDestructiveActions(animate);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        final ConversationListFragment convList = getConversationListFragment();
        if (hasFocus && convList != null && convList.isVisible()) {
            // The conversation list is visible.
            informCursorVisiblity(true);
        }
    }

    /**
     * Set the account, and carry out all the account-related changes that rely on this.
     * @param account
     */
    private void setAccount(Account account) {
        if (account == null) {
            LogUtils.w(LOG_TAG, new Error(),
                    "AAC ignoring null (presumably invalid) account restoration");
            return;
        }
        LogUtils.d(LOG_TAG, "AbstractActivityController.setAccount(): account = %s", account.uri);
        mAccount = account;
        // Only change AAC state here. Do *not* modify any other object's state. The object
        // should listen on account changes.
        restartOptionalLoader(LOADER_RECENT_FOLDERS);
        mActivity.invalidateOptionsMenu();
        disableNotificationsOnAccountChange(mAccount);
        restartOptionalLoader(LOADER_ACCOUNT_UPDATE_CURSOR);
        MailAppProvider.getInstance().setLastViewedAccount(mAccount.uri.toString());

        if (account.settings == null) {
            LogUtils.w(LOG_TAG, new Error(), "AAC ignoring account with null settings.");
            return;
        }
        mAccountObservers.notifyChanged();
        perhapsEnterWaitMode();
    }

    /**
     * Restore the state from the previous bundle. Subclasses should call this
     * method from the parent class, since it performs important UI
     * initialization.
     *
     * @param savedState
     */
    @Override
    public void onRestoreInstanceState(Bundle savedState) {
        LogUtils.d(LOG_TAG, "IN AAC.onRestoreInstanceState");
        if (savedState.containsKey(SAVED_CONVERSATION)) {
            // Open the conversation.
            final Conversation conversation = savedState.getParcelable(SAVED_CONVERSATION);
            if (conversation != null && conversation.position < 0) {
                // Set the position to 0 on this conversation, as we don't know where it is
                // in the list
                conversation.position = 0;
            }
            showConversation(conversation);
        }

        if (savedState.containsKey(SAVED_TOAST_BAR_OP)) {
            ToastBarOperation op = savedState.getParcelable(SAVED_TOAST_BAR_OP);
            if (op != null) {
                if (op.getType() == ToastBarOperation.UNDO) {
                    onUndoAvailable(op);
                } else if (op.getType() == ToastBarOperation.ERROR) {
                    onError(mFolder, true);
                }
            }
        }
        final String folderString = savedState.getString(SAVED_HIERARCHICAL_FOLDER, null);
        if (!TextUtils.isEmpty(folderString)) {
            mFolderListFolder = Folder.fromString(folderString);
        }
        final ConversationListFragment convListFragment = getConversationListFragment();
        if (convListFragment != null) {
            convListFragment.getAnimatedAdapter().onRestoreInstanceState(savedState);
        }
        /**
         * Restore the state of selected conversations. This needs to be done after the correct mode
         * is set and the action bar is fully initialized. If not, several key pieces of state
         * information will be missing, and the split views may not be initialized correctly.
         * @param savedState
         */
        restoreSelectedConversations(savedState);
    }

    /**
     * Handle an intent to open the app. This method is called only when there is no saved state,
     * so we need to set state that wasn't set before. It is correct to change the viewmode here
     * since it has not been previously set.
     * @param intent
     */
    private void handleIntent(Intent intent) {
        boolean handled = false;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            if (intent.hasExtra(Utils.EXTRA_ACCOUNT)) {
                setAccount(Account.newinstance(intent.getStringExtra(Utils.EXTRA_ACCOUNT)));
            }
            if (mAccount == null) {
                return;
            }
            final boolean isConversationMode = intent.hasExtra(Utils.EXTRA_CONVERSATION);
            if (isConversationMode && mViewMode.getMode() == ViewMode.UNKNOWN) {
                mViewMode.enterConversationMode();
            } else {
                mViewMode.enterConversationListMode();
            }
            final Folder folder = intent.hasExtra(Utils.EXTRA_FOLDER) ?
                    Folder.fromString(intent.getStringExtra(Utils.EXTRA_FOLDER)) : null;
            if (folder != null) {
                onFolderChanged(folder);
                handled = true;
            }

            if (isConversationMode) {
                // Open the conversation.
                LogUtils.d(LOG_TAG, "SHOW THE CONVERSATION at %s",
                        intent.getParcelableExtra(Utils.EXTRA_CONVERSATION));
                final Conversation conversation =
                        intent.getParcelableExtra(Utils.EXTRA_CONVERSATION);
                if (conversation != null && conversation.position < 0) {
                    // Set the position to 0 on this conversation, as we don't know where it is
                    // in the list
                    conversation.position = 0;
                }
                showConversation(conversation);
                handled = true;
            }

            if (!handled) {
                // We have an account, but nothing else: load the default inbox.
                loadAccountInbox();
            }
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            if (intent.hasExtra(Utils.EXTRA_ACCOUNT)) {
                mHaveSearchResults = false;
                // Save this search query for future suggestions.
                final String query = intent.getStringExtra(SearchManager.QUERY);
                final String authority = mContext.getString(R.string.suggestions_authority);
                final SearchRecentSuggestions suggestions = new SearchRecentSuggestions(
                        mContext, authority, SuggestionsProvider.MODE);
                suggestions.saveRecentQuery(query, null);
                setAccount((Account) intent.getParcelableExtra(Utils.EXTRA_ACCOUNT));
                fetchSearchFolder(intent);
                if (shouldEnterSearchConvMode()) {
                    mViewMode.enterSearchResultsConversationMode();
                } else {
                    mViewMode.enterSearchResultsListMode();
                }
            } else {
                LogUtils.e(LOG_TAG, "Missing account extra from search intent.  Finishing");
                mActivity.finish();
            }
        }
        if (mAccount != null) {
            restartOptionalLoader(LOADER_ACCOUNT_UPDATE_CURSOR);
        }
    }

    /**
     * Returns true if we should enter conversation mode with search.
     */
    protected final boolean shouldEnterSearchConvMode() {
        return mHaveSearchResults && Utils.showTwoPaneSearchResults(mActivity.getActivityContext());
    }

    /**
     * Copy any selected conversations stored in the saved bundle into our selection set,
     * triggering {@link ConversationSetObserver} callbacks as our selection set changes.
     *
     */
    private final void restoreSelectedConversations(Bundle savedState) {
        if (savedState == null) {
            mSelectedSet.clear();
            return;
        }
        final ConversationSelectionSet selectedSet = savedState.getParcelable(SAVED_SELECTED_SET);
        if (selectedSet == null || selectedSet.isEmpty()) {
            mSelectedSet.clear();
            return;
        }

        // putAll will take care of calling our registered onSetPopulated method
        mSelectedSet.putAll(selectedSet);
    }

    @Override
    public SubjectDisplayChanger getSubjectDisplayChanger() {
        return mActionBarView;
    }

    private final void showConversation(Conversation conversation) {
        showConversation(conversation, false /* inLoaderCallbacks */);
    }

    /**
     * Show the conversation provided in the arguments. It is safe to pass a null conversation
     * object, which is a signal to back out of conversation view mode.
     * Child classes must call super.showConversation() <b>before</b> their own implementations.
     * @param conversation
     * @param inLoaderCallbacks true if the method is called as a result of
     * {@link #onLoadFinished(Loader, Cursor)}
     */
    protected void showConversation(Conversation conversation, boolean inLoaderCallbacks) {
        // Set the current conversation just in case it wasn't already set.
        setCurrentConversation(conversation);
        // Add the folder that we were viewing to the recent folders list.
        // TODO: this may need to be fine tuned.  If this is the signal that is indicating that
        // the list is shown to the user, this could fire in one pane if the user goes directly
        // to a conversation
        updateRecentFolderList();
    }

    /**
     * Children can override this method, but they must call super.showWaitForInitialization().
     * {@inheritDoc}
     */
    @Override
    public void showWaitForInitialization() {
        mViewMode.enterWaitingForInitializationMode();
        mWaitFragment = WaitFragment.newInstance(mAccount);
    }

    private void updateWaitMode() {
        final FragmentManager manager = mActivity.getFragmentManager();
        final WaitFragment waitFragment =
                (WaitFragment)manager.findFragmentByTag(TAG_WAIT);
        if (waitFragment != null) {
            waitFragment.updateAccount(mAccount);
        }
    }

    /**
     * Remove the "Waiting for Initialization" fragment. Child classes are free to override this
     * method, though they must call the parent implementation <b>after</b> they do anything.
     */
    protected void hideWaitForInitialization() {
        mWaitFragment = null;
    }

    /**
     * Use the instance variable and the wait fragment's tag to get the wait fragment.  This is
     * far superior to using the value of mWaitFragment, which might be invalid or might refer
     * to a fragment after it has been destroyed.
     * @return
     */
    protected final WaitFragment getWaitFragment() {
        final FragmentManager manager = mActivity.getFragmentManager();
        final WaitFragment waitFrag = (WaitFragment) manager.findFragmentByTag(TAG_WAIT);
        if (waitFrag != null) {
            // The Fragment Manager knows better, so use its instance.
            mWaitFragment = waitFrag;
        }
        return mWaitFragment;
    }

    /**
     * Returns true if we are waiting for the account to sync, and cannot show any folders or
     * conversation for the current account yet.
     */
    private boolean inWaitMode() {
        final FragmentManager manager = mActivity.getFragmentManager();
        final WaitFragment waitFragment = getWaitFragment();
        if (waitFragment != null) {
            final Account fragmentAccount = waitFragment.getAccount();
            return fragmentAccount != null && fragmentAccount.uri.equals(mAccount.uri) &&
                    mViewMode.getMode() == ViewMode.WAITING_FOR_ACCOUNT_INITIALIZATION;
        }
        return false;
    }

    /**
     * Children can override this method, but they must call super.showConversationList().
     * {@inheritDoc}
     */
    @Override
    public void showConversationList(ConversationListContext listContext) {
    }

    @Override
    public final void onConversationSelected(Conversation conversation, boolean inLoaderCallbacks) {
        // Only animate destructive actions if we are going to be showing the
        // conversation list when we show the next conversation.
        commitDestructiveActions(Utils.useTabletUI(mContext));
        showConversation(conversation, inLoaderCallbacks);
    }

    @Override
    public Conversation getCurrentConversation() {
        return mCurrentConversation;
    }

    /**
     * Set the current conversation. This is the conversation on which all actions are performed.
     * Do not modify mCurrentConversation except through this method, which makes it easy to
     * perform common actions associated with changing the current conversation.
     * @param conversation
     */
    @Override
    public void setCurrentConversation(Conversation conversation) {
        // Must be the first call because this sets conversation.position if a cursor is available.
        mTracker.initialize(conversation);
        mCurrentConversation = conversation;

        if (mCurrentConversation != null) {
            mActionBarView.setCurrentConversation(mCurrentConversation);
            getSubjectDisplayChanger().setSubject(mCurrentConversation.subject);
            mActivity.invalidateOptionsMenu();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        switch (id) {
            case LOADER_ACCOUNT_CURSOR:
                return new CursorLoader(mContext, MailAppProvider.getAccountsUri(),
                        UIProvider.ACCOUNTS_PROJECTION, null, null, null);
            case LOADER_FOLDER_CURSOR:
                final CursorLoader loader = new CursorLoader(mContext, mFolder.uri,
                        UIProvider.FOLDERS_PROJECTION, null, null, null);
                loader.setUpdateThrottle(mFolderItemUpdateDelayMs);
                return loader;
            case LOADER_RECENT_FOLDERS:
                if (mAccount != null && mAccount.recentFolderListUri != null) {
                    return new CursorLoader(mContext, mAccount.recentFolderListUri,
                            UIProvider.FOLDERS_PROJECTION, null, null, null);
                }
                break;
            case LOADER_ACCOUNT_INBOX:
                final Uri defaultInbox = Settings.getDefaultInboxUri(mAccount.settings);
                final Uri inboxUri = defaultInbox.equals(Uri.EMPTY) ?
                    mAccount.folderListUri : defaultInbox;
                LogUtils.d(LOG_TAG, "Loading the default inbox: %s", inboxUri);
                if (inboxUri != null) {
                    return new CursorLoader(mContext, inboxUri, UIProvider.FOLDERS_PROJECTION, null,
                            null, null);
                }
                break;
            case LOADER_SEARCH:
                return Folder.forSearchResults(mAccount,
                        args.getString(ConversationListContext.EXTRA_SEARCH_QUERY),
                        mActivity.getActivityContext());
            case LOADER_ACCOUNT_UPDATE_CURSOR:
                return new CursorLoader(mContext, mAccount.uri, UIProvider.ACCOUNTS_PROJECTION,
                        null, null, null);
            default:
                LogUtils.wtf(LOG_TAG, "Loader returned unexpected id: %d", id);
        }
        return null;
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {

    }

    /**
     * {@link LoaderManager} currently has a bug in
     * {@link LoaderManager#restartLoader(int, Bundle, android.app.LoaderManager.LoaderCallbacks)}
     * where, if a previous onCreateLoader returned a null loader, this method will NPE. Work around
     * this bug by destroying any loaders that may have been created as null (essentially because
     * they are optional loads, and may not apply to a particular account).
     * <p>
     * A simple null check before restarting a loader will not work, because that would not
     * give the controller a chance to invalidate UI corresponding the prior loader result.
     *
     * @param id loader ID to safely restart
     */
    private void restartOptionalLoader(int id) {
        final LoaderManager lm = mActivity.getLoaderManager();
        lm.destroyLoader(id);
        lm.restartLoader(id, Bundle.EMPTY, this);
    }

    @Override
    public void registerConversationListObserver(DataSetObserver observer) {
        mConversationListObservable.registerObserver(observer);
    }

    @Override
    public void unregisterConversationListObserver(DataSetObserver observer) {
        mConversationListObservable.unregisterObserver(observer);
    }

    @Override
    public void registerFolderObserver(DataSetObserver observer) {
        mFolderObservable.registerObserver(observer);
    }

    @Override
    public void unregisterFolderObserver(DataSetObserver observer) {
        mFolderObservable.unregisterObserver(observer);
    }

    @Override
    public void registerConversationLoadedObserver(DataSetObserver observer) {
        mPagerController.registerConversationLoadedObserver(observer);
    }

    @Override
    public void unregisterConversationLoadedObserver(DataSetObserver observer) {
        mPagerController.unregisterConversationLoadedObserver(observer);
    }

    /**
     * Returns true if the number of accounts is different, or if the current account has been
     * removed from the device
     * @param accountCursor
     * @return
     */
    private boolean accountsUpdated(Cursor accountCursor) {
        // Check to see if the current account hasn't been set, or the account cursor is empty
        if (mAccount == null || !accountCursor.moveToFirst()) {
            return true;
        }

        // Check to see if the number of accounts are different, from the number we saw on the last
        // updated
        if (mCurrentAccountUris.size() != accountCursor.getCount()) {
            return true;
        }

        // Check to see if the account list is different or if the current account is not found in
        // the cursor.
        boolean foundCurrentAccount = false;
        do {
            final Uri accountUri =
                    Uri.parse(accountCursor.getString(UIProvider.ACCOUNT_URI_COLUMN));
            if (!foundCurrentAccount && mAccount.uri.equals(accountUri)) {
                foundCurrentAccount = true;
            }
            // Is there a new account that we do not know about?
            if (!mCurrentAccountUris.contains(accountUri)) {
                return true;
            }
        } while (accountCursor.moveToNext());

        // As long as we found the current account, the list hasn't been updated
        return !foundCurrentAccount;
    }

    /**
     * Updates accounts for the app. If the current account is missing, the first
     * account in the list is set to the current account (we <em>have</em> to choose something).
     *
     * @param accounts cursor into the AccountCache
     * @return true if the update was successful, false otherwise
     */
    private boolean updateAccounts(Cursor accounts) {
        if (accounts == null || !accounts.moveToFirst()) {
            return false;
        }

        final Account[] allAccounts = Account.getAllAccounts(accounts);
        // A match for the current account's URI in the list of accounts.
        Account currentFromList = null;

        // Save the uris for the accounts and find the current account in the updated cursor.
        mCurrentAccountUris.clear();
        for (final Account account : allAccounts) {
            LogUtils.d(LOG_TAG, "updateAccounts(%s)", account);
            mCurrentAccountUris.add(account.uri);
            if (mAccount != null && account.uri.equals(mAccount.uri)) {
                currentFromList = account;
            }
        }

        // 1. current account is already set and is in allAccounts:
        //    1a. It has changed -> load the updated account.
        //    2b. It is unchanged -> no-op
        // 2. current account is set and is not in allAccounts -> pick first (acct was deleted?)
        // 3. saved preference has an account -> pick that one
        // 4. otherwise just pick first

        boolean accountChanged = false;
        /// Assume case 4, initialize to first account, and see if we can find anything better.
        Account newAccount = allAccounts[0];
        if (currentFromList != null) {
            // Case 1: Current account exists but has changed
            if (!currentFromList.equals(mAccount)) {
                newAccount = currentFromList;
                accountChanged = true;
            }
            // Case 1b: else, current account is unchanged: nothing to do.
        } else {
            // Case 2: Current account is not in allAccounts, the account needs to change.
            accountChanged = true;
            if (mAccount == null) {
                // Case 3: Check for last viewed account, and check if it exists in the list.
                final String lastAccountUri = MailAppProvider.getInstance().getLastViewedAccount();
                if (lastAccountUri != null) {
                    for (final Account account : allAccounts) {
                        if (lastAccountUri.equals(account.uri.toString())) {
                            newAccount = account;
                            break;
                        }
                    }
                }
            }
        }
        if (accountChanged) {
            onAccountChanged(newAccount);
        }
        // Whether we have updated the current account or not, we need to update the list of
        // accounts in the ActionBar.
        mActionBarView.setAccounts(allAccounts);
        return (allAccounts.length > 0);
    }

    private void disableNotifications() {
        mNewEmailReceiver.activate(mContext, this);
    }

    private void enableNotifications() {
        mNewEmailReceiver.deactivate();
    }

    private void disableNotificationsOnAccountChange(Account account) {
        // If the new mail suppression receiver is activated for a different account, we want to
        // activate it for the new account.
        if (mNewEmailReceiver.activated() &&
                !mNewEmailReceiver.notificationsDisabledForAccount(account)) {
            // Deactivate the current receiver, otherwise multiple receivers may be registered.
            mNewEmailReceiver.deactivate();
            mNewEmailReceiver.activate(mContext, this);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        // We want to reinitialize only if we haven't ever been initialized, or
        // if the current account has vanished.
        if (data == null) {
            LogUtils.e(LOG_TAG, "Received null cursor from loader id: %d", loader.getId());
        }
        switch (loader.getId()) {
            case LOADER_ACCOUNT_CURSOR:
                if (data == null) {
                    // Nothing useful to do if we have no valid data.
                    break;
                }
                if (data.getCount() == 0) {
                    // If an empty cursor is returned, the MailAppProvider is indicating that
                    // no accounts have been specified.  We want to navigate to the "add account"
                    // activity that will handle the intent returned by the MailAppProvider

                    // If the MailAppProvider believes that all accounts have been loaded, and the
                    // account list is still empty, we want to prompt the user to add an account
                    final Bundle extras = data.getExtras();
                    final boolean accountsLoaded =
                            extras.getInt(AccountCursorExtraKeys.ACCOUNTS_LOADED) != 0;

                    if (accountsLoaded) {
                        final Intent noAccountIntent = MailAppProvider.getNoAccountIntent(mContext);
                        if (noAccountIntent != null) {
                            mActivity.startActivityForResult(noAccountIntent,
                                    ADD_ACCOUNT_REQUEST_CODE);
                        }
                    }
                } else {
                    final boolean accountListUpdated = accountsUpdated(data);
                    if (!isLoaderInitialized || accountListUpdated) {
                        isLoaderInitialized = updateAccounts(data);
                    }
                }
                break;
            case LOADER_ACCOUNT_UPDATE_CURSOR:
                // We have gotten an update for current account.

                // Make sure that this is an update for the current account
                if (data != null && data.moveToFirst()) {
                    final Account updatedAccount = new Account(data);

                    if (updatedAccount.uri.equals(mAccount.uri)) {
                        // Keep a reference to the previous settings object
                        final Settings previousSettings = mAccount.settings;

                        // Update the controller's reference to the current account
                        mAccount = updatedAccount;
                        LogUtils.d(LOG_TAG, "AbstractActivityController.onLoadFinished(): "
                                + "mAccount = %s", mAccount.uri);

                        // Only notify about a settings change if something differs
                        if (!Objects.equal(mAccount.settings, previousSettings)) {
                            mAccountObservers.notifyChanged();
                        }
                        perhapsEnterWaitMode();
                    } else {
                        LogUtils.e(LOG_TAG, "Got update for account: %s with current account: %s",
                                updatedAccount.uri, mAccount.uri);
                        // We need to restart the loader, so the correct account information will
                        // be returned
                        restartOptionalLoader(LOADER_ACCOUNT_UPDATE_CURSOR);
                    }
                }
                break;
            case LOADER_FOLDER_CURSOR:
                // Check status of the cursor.
                if (data != null && data.moveToFirst()) {
                    final Folder folder = new Folder(data);
                    LogUtils.d(LOG_TAG, "FOLDER STATUS = %d", folder.syncStatus);
                    setHasFolderChanged(folder);
                    mFolder = folder;
                    mFolderObservable.notifyChanged();
                } else {
                    LogUtils.d(LOG_TAG, "Unable to get the folder %s",
                            mFolder != null ? mAccount.name : "");
                }
                break;
            case LOADER_RECENT_FOLDERS:
                // Few recent folders and we are running on a phone? Populate the default recents.
                // The number of default recent folders is at least 2: every provider has at
                // least two folders, and the recent folder count never decreases. Having a single
                // recent folder is an erroneous case, and we can gracefully recover by populating
                // default recents. The default recents will not stomp on the existing value: it
                // will be shown in addition to the default folders: the max number of recent
                // folders is more than 1+num(defaultRecents).
                if (data != null && data.getCount() <= 1 && !Utils.useTabletUI(mContext)) {
                    final class PopulateDefault extends AsyncTask<Uri, Void, Void> {
                        @Override
                        protected Void doInBackground(Uri... uri) {
                            // Asking for an update on the URI and ignore the result.
                            final ContentResolver resolver = mContext.getContentResolver();
                            resolver.update(uri[0], null, null, null);
                            return null;
                        }
                    }
                    final Uri uri = mAccount.defaultRecentFolderListUri;
                    LogUtils.v(LOG_TAG, "Default recents at %s", uri);
                    new PopulateDefault().execute(uri);
                    break;
                }
                LogUtils.v(LOG_TAG, "Reading recent folders from the cursor.");
                loadRecentFolders(data);
                break;
            case LOADER_ACCOUNT_INBOX:
                if (data != null && !data.isClosed() && data.moveToFirst()) {
                    Folder inbox = new Folder(data);
                    onFolderChanged(inbox);
                    // Just want to get the inbox, don't care about updates to it
                    // as this will be tracked by the folder change listener.
                    mActivity.getLoaderManager().destroyLoader(LOADER_ACCOUNT_INBOX);
                } else {
                    LogUtils.d(LOG_TAG, "Unable to get the account inbox for account %s",
                            mAccount != null ? mAccount.name : "");
                }
                break;
            case LOADER_SEARCH:
                if (data != null && data.getCount() > 0) {
                    data.moveToFirst();
                    final Folder search = new Folder(data);
                    updateFolder(search);
                    mConvListContext = ConversationListContext.forSearchQuery(mAccount, mFolder,
                            mActivity.getIntent()
                                    .getStringExtra(UIProvider.SearchQueryParameters.QUERY));
                    showConversationList(mConvListContext);
                    mActivity.invalidateOptionsMenu();
                    mHaveSearchResults = search.totalCount > 0;
                    mActivity.getLoaderManager().destroyLoader(LOADER_SEARCH);
                } else {
                    LogUtils.e(LOG_TAG, "Null or empty cursor returned by LOADER_SEARCH loader");
                }
                break;
        }
    }


    /**
     * Destructive actions on Conversations. This class should only be created by controllers, and
     * clients should only require {@link DestructiveAction}s, not specific implementations of the.
     * Only the controllers should know what kind of destructive actions are being created.
     */
    public class ConversationAction implements DestructiveAction {
        /**
         * The action to be performed. This is specified as the resource ID of the menu item
         * corresponding to this action: R.id.delete, R.id.report_spam, etc.
         */
        private final int mAction;
        /** The action will act upon these conversations */
        private final Collection<Conversation> mTarget;
        /** Whether this destructive action has already been performed */
        private boolean mCompleted;
        /** Whether this is an action on the currently selected set. */
        private final boolean mIsSelectedSet;

        /**
         * Create a listener object. action is one of four constants: R.id.y_button (archive),
         * R.id.delete , R.id.mute, and R.id.report_spam.
         * @param action
         * @param target Conversation that we want to apply the action to.
         * @param isBatch whether the conversations are in the currently selected batch set.
         */
        public ConversationAction(int action, Collection<Conversation> target, boolean isBatch) {
            mAction = action;
            mTarget = ImmutableList.copyOf(target);
            mIsSelectedSet = isBatch;
        }

        /**
         * The action common to child classes. This performs the action specified in the constructor
         * on the conversations given here.
         */
        @Override
        public void performAction() {
            if (isPerformed()) {
                return;
            }
            boolean undoEnabled = mAccount.supportsCapability(AccountCapabilities.UNDO);

            // Are we destroying the currently shown conversation? Show the next one.
            if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)){
                LogUtils.d(LOG_TAG, "ConversationAction.performAction():"
                        + "\nmTarget=%s\nCurrent=%s",
                        Conversation.toString(mTarget), mCurrentConversation);
            }

            if (mConversationListCursor == null) {
                LogUtils.e(LOG_TAG, "null ConversationCursor in ConversationAction.performAction():"
                        + "\nmTarget=%s\nCurrent=%s",
                        Conversation.toString(mTarget), mCurrentConversation);
                return;
            }

            switch (mAction) {
                case R.id.archive:
                    LogUtils.d(LOG_TAG, "Archiving");
                    mConversationListCursor.archive(mContext, mTarget);
                    break;
                case R.id.delete:
                    LogUtils.d(LOG_TAG, "Deleting");
                    mConversationListCursor.delete(mContext, mTarget);
                    if (mFolder.supportsCapability(FolderCapabilities.DELETE_ACTION_FINAL)) {
                        undoEnabled = false;
                    }
                    break;
                case R.id.mute:
                    LogUtils.d(LOG_TAG, "Muting");
                    if (mFolder.supportsCapability(FolderCapabilities.DESTRUCTIVE_MUTE)) {
                        for (Conversation c : mTarget) {
                            c.localDeleteOnUpdate = true;
                        }
                    }
                    mConversationListCursor.mute(mContext, mTarget);
                    break;
                case R.id.report_spam:
                    LogUtils.d(LOG_TAG, "Reporting spam");
                    mConversationListCursor.reportSpam(mContext, mTarget);
                    break;
                case R.id.mark_not_spam:
                    LogUtils.d(LOG_TAG, "Marking not spam");
                    mConversationListCursor.reportNotSpam(mContext, mTarget);
                    break;
                case R.id.report_phishing:
                    LogUtils.d(LOG_TAG, "Reporting phishing");
                    mConversationListCursor.reportPhishing(mContext, mTarget);
                    break;
                case R.id.remove_star:
                    LogUtils.d(LOG_TAG, "Removing star");
                    // Star removal is destructive in the Starred folder.
                    mConversationListCursor.updateBoolean(mContext, mTarget,
                            ConversationColumns.STARRED, false);
                    break;
                case R.id.mark_not_important:
                    LogUtils.d(LOG_TAG, "Marking not-important");
                    // Marking not important is destructive in a mailbox
                    // containing only important messages
                    if (mFolder != null && mFolder.isImportantOnly()) {
                        for (Conversation conv : mTarget) {
                            conv.localDeleteOnUpdate = true;
                        }
                    }
                    mConversationListCursor.updateInt(mContext, mTarget,
                            ConversationColumns.PRIORITY, UIProvider.ConversationPriority.LOW);
                    break;
                case R.id.discard_drafts:
                    LogUtils.d(LOG_TAG, "Discarding draft messages");
                    // Discarding draft messages is destructive in a "draft" mailbox
                    if (mFolder != null && mFolder.isDraft()) {
                        for (Conversation conv : mTarget) {
                            conv.localDeleteOnUpdate = true;
                        }
                    }
                    mConversationListCursor.discardDrafts(mContext, mTarget);
                    // We don't support undoing discarding drafts
                    undoEnabled = false;
                    break;
            }
            if (undoEnabled) {
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        onUndoAvailable(new ToastBarOperation(mTarget.size(), mAction,
                                ToastBarOperation.UNDO, mIsSelectedSet));
                    }
                }, mShowUndoBarDelay);
            }
            refreshConversationList();
            if (mIsSelectedSet) {
                mSelectedSet.clear();
            }
        }

        /**
         * Returns true if this action has been performed, false otherwise.
         *
         */
        private synchronized boolean isPerformed() {
            if (mCompleted) {
                return true;
            }
            mCompleted = true;
            return false;
        }
    }

    /**
     * Get a destructive action for a menu action.
     * This is a temporary method, to control the profusion of {@link DestructiveAction} classes
     * that are created. Please do not copy this paradigm.
     * @param action the resource ID of the menu action: R.id.delete, for example
     * @param target the conversations to act upon.
     * @return a {@link DestructiveAction} that performs the specified action.
     */
    private final DestructiveAction getAction(int action, Collection<Conversation> target) {
        final DestructiveAction da = new ConversationAction(action, target, false);
        registerDestructiveAction(da);
        return da;
    }

    // Called from the FolderSelectionDialog after a user is done selecting folders to assign the
    // conversations to.
    @Override
    public final void assignFolder(Collection<FolderOperation> folderOps,
            Collection<Conversation> target, boolean batch, boolean showUndo) {
        // Actions are destructive only when the current folder can be assigned
        // to (which is the same as being able to un-assign a conversation from the folder) and
        // when the list of folders contains the current folder.
        final boolean isDestructive = mFolder
                .supportsCapability(FolderCapabilities.CAN_ACCEPT_MOVED_MESSAGES)
                && FolderOperation.isDestructive(folderOps, mFolder);
        LogUtils.d(LOG_TAG, "onFolderChangesCommit: isDestructive = %b", isDestructive);
        if (isDestructive) {
            for (final Conversation c : target) {
                c.localDeleteOnUpdate = true;
            }
        }
        final DestructiveAction folderChange;
        // Update the UI elements depending no their visibility and availability
        // TODO(viki): Consolidate this into a single method requestDelete.
        if (isDestructive) {
            folderChange = getDeferredFolderChange(target, folderOps, isDestructive,
                    batch, showUndo);
            delete(0, target, folderChange);
        } else {
            folderChange = getFolderChange(target, folderOps, isDestructive,
                    batch, showUndo);
            requestUpdate(target, folderChange);
        }
    }

    @Override
    public final void onRefreshRequired() {
        if (isAnimating() || isDragging()) {
            LogUtils.d(LOG_TAG, "onRefreshRequired: delay until animating done");
            return;
        }
        // Refresh the query in the background
        if (mConversationListCursor.isRefreshRequired()) {
            mConversationListCursor.refresh();
        }
    }

    @Override
    public void startDragMode() {
        mIsDragHappening = true;
    }

    @Override
    public void stopDragMode() {
        mIsDragHappening = false;
        if (mConversationListCursor.isRefreshReady()) {
            LogUtils.d(LOG_TAG, "Stopped animating: try sync");
            onRefreshReady();
        }

        if (mConversationListCursor.isRefreshRequired()) {
            LogUtils.d(LOG_TAG, "Stopped animating: refresh");
            mConversationListCursor.refresh();
        }
    }

    private boolean isDragging() {
        return mIsDragHappening;
    }

    @Override
    public boolean isAnimating() {
        boolean isAnimating = false;
        ConversationListFragment convListFragment = getConversationListFragment();
        if (convListFragment != null) {
            AnimatedAdapter adapter = convListFragment.getAnimatedAdapter();
            if (adapter != null) {
                isAnimating = adapter.isAnimating();
            }
        }
        return isAnimating;
    }

    /**
     * Called when the {@link ConversationCursor} is changed or has new data in it.
     * <p>
     * {@inheritDoc}
     */
    @Override
    public final void onRefreshReady() {
        LogUtils.d(LOG_TAG, "Received refresh ready callback for folder %s",
                mFolder != null ? mFolder.id : "-1");
        if (!isAnimating()) {
            // Swap cursors
            mConversationListCursor.sync();
        }
        mTracker.onCursorUpdated();
        perhapsShowFirstSearchResult();
    }

    @Override
    public final void onDataSetChanged() {
        updateConversationListFragment();
        mConversationListObservable.notifyChanged();
        mSelectedSet.validateAgainstCursor(mConversationListCursor);
    }

    /**
     * If the Conversation List Fragment is visible, updates the fragment.
     */
    private final void updateConversationListFragment() {
        final ConversationListFragment convList = getConversationListFragment();
        if (convList != null) {
            refreshConversationList();
            if (convList.isVisible()) {
                informCursorVisiblity(true);
            }
        }
    }

    /**
     * This class handles throttled refresh of the conversation list
     */
    static class RefreshTimerTask extends TimerTask {
        final Handler mHandler;
        final AbstractActivityController mController;

        RefreshTimerTask(AbstractActivityController controller, Handler handler) {
            mHandler = handler;
            mController = controller;
        }

        @Override
        public void run() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    LogUtils.d(LOG_TAG, "Delay done... calling onRefreshRequired");
                    mController.onRefreshRequired();
                }});
        }
    }

    /**
     * Cancel the refresh task, if it's running
     */
    private void cancelRefreshTask () {
        if (mConversationListRefreshTask != null) {
            mConversationListRefreshTask.cancel();
            mConversationListRefreshTask = null;
        }
    }

    private void loadRecentFolders(Cursor data) {
        mRecentFolderList.loadFromUiProvider(data);
        if (isAnimating()) {
            mRecentsDataUpdated = true;
        } else {
            mRecentFolderObservers.notifyChanged();
        }
    }

    @Override
    public void onAnimationEnd(AnimatedAdapter animatedAdapter) {
        if (mConversationListCursor == null) {
            LogUtils.e(LOG_TAG, "null ConversationCursor in onAnimationEnd");
            return;
        }
        if (mConversationListCursor.isRefreshReady()) {
            LogUtils.d(LOG_TAG, "Stopped animating: try sync");
            onRefreshReady();
        }

        if (mConversationListCursor.isRefreshRequired()) {
            LogUtils.d(LOG_TAG, "Stopped animating: refresh");
            mConversationListCursor.refresh();
        }
        if (mRecentsDataUpdated) {
            mRecentsDataUpdated = false;
            mRecentFolderObservers.notifyChanged();
        }
        FolderListFragment frag = this.getFolderListFragment();
        if (frag != null) {
            frag.onAnimationEnd();
        }
    }

    @Override
    public void onSetEmpty() {
    }

    @Override
    public void onSetPopulated(ConversationSelectionSet set) {
        final ConversationListFragment convList = getConversationListFragment();
        if (convList == null) {
            return;
        }
        mCabActionMenu = new SelectedConversationsActionMenu(mActivity, set, mFolder,
                (SwipeableListView) convList.getListView());
        enableCabMode();
    }

    @Override
    public void onSetChanged(ConversationSelectionSet set) {
        // Do nothing. We don't care about changes to the set.
    }

    @Override
    public ConversationSelectionSet getSelectedSet() {
        return mSelectedSet;
    }

    /**
     * Disable the Contextual Action Bar (CAB). The selected set is not changed.
     */
    protected void disableCabMode() {
        // Commit any previous destructive actions when entering/ exiting CAB mode.
        commitDestructiveActions(true);
        if (mCabActionMenu != null) {
            mCabActionMenu.deactivate();
        }
    }

    /**
     * Re-enable the CAB menu if required. The selection set is not changed.
     */
    protected void enableCabMode() {
        if (mCabActionMenu != null) {
            mCabActionMenu.activate();
        }
    }

    /**
     * Unselect conversations and exit CAB mode.
     */
    protected final void exitCabMode() {
        mSelectedSet.clear();
    }

    @Override
    public void startSearch() {
        if (mAccount == null) {
            // We cannot search if there is no account. Drop the request to the floor.
            LogUtils.d(LOG_TAG, "AbstractActivityController.startSearch(): null account");
            return;
        }
        if (mAccount.supportsCapability(UIProvider.AccountCapabilities.LOCAL_SEARCH)
                | mAccount.supportsCapability(UIProvider.AccountCapabilities.SERVER_SEARCH)) {
            onSearchRequested(mActionBarView.getQuery());
        } else {
            Toast.makeText(mActivity.getActivityContext(), mActivity.getActivityContext()
                    .getString(R.string.search_unsupported), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void exitSearchMode() {
        if (mViewMode.getMode() == ViewMode.SEARCH_RESULTS_LIST) {
            mActivity.finish();
        }
    }

    /**
     * Supports dragging conversations to a folder.
     */
    @Override
    public boolean supportsDrag(DragEvent event, Folder folder) {
        return (folder != null
                && event != null
                && event.getClipDescription() != null
                && folder.supportsCapability
                    (UIProvider.FolderCapabilities.CAN_ACCEPT_MOVED_MESSAGES)
                && folder.supportsCapability
                    (UIProvider.FolderCapabilities.CAN_HOLD_MAIL)
                && !mFolder.uri.equals(folder.uri));
    }

    /**
     * Handles dropping conversations to a folder.
     */
    @Override
    public void handleDrop(DragEvent event, final Folder folder) {
        if (!supportsDrag(event, folder)) {
            return;
        }
        if (folder.type == UIProvider.FolderType.STARRED) {
            // Moving a conversation to the starred folder adds the star and
            // removes the current label
            handleDropInStarred(folder);
            return;
        }
        if (mFolder.type == UIProvider.FolderType.STARRED) {
            handleDragFromStarred(folder);
            return;
        }
        final ArrayList<FolderOperation> dragDropOperations = new ArrayList<FolderOperation>();
        final Collection<Conversation> conversations = mSelectedSet.values();
        // Add the drop target folder.
        dragDropOperations.add(new FolderOperation(folder, true));
        // Remove the current folder unless the user is viewing "all".
        // That operation should just add the new folder.
        boolean isDestructive = !mFolder.isViewAll()
                && mFolder.supportsCapability
                    (UIProvider.FolderCapabilities.CAN_ACCEPT_MOVED_MESSAGES);
        if (isDestructive) {
            dragDropOperations.add(new FolderOperation(mFolder, false));
        }
        // Drag and drop is destructive: we remove conversations from the
        // current folder.
        final DestructiveAction action = getFolderChange(conversations, dragDropOperations,
                isDestructive, true, true);
        if (isDestructive) {
            delete(0, conversations, action);
        } else {
            action.performAction();
        }
    }

    private void handleDragFromStarred(Folder folder) {
        final Collection<Conversation> conversations = mSelectedSet.values();
        // The conversation list deletes and performs the action if it exists.
        final ConversationListFragment convListFragment = getConversationListFragment();
        // There should always be a convlistfragment, or the user could not have
        // dragged/ dropped conversations.
        if (convListFragment != null) {
            LogUtils.d(LOG_TAG, "AAC.requestDelete: ListFragment is handling delete.");
            ArrayList<ConversationOperation> ops = new ArrayList<ConversationOperation>();
            ContentValues values = new ContentValues();
            for (Conversation target : conversations) {
                HashMap<Uri, Folder> targetFolders = Folder.hashMapForFolders(target
                        .getRawFolders());
                targetFolders.put(folder.uri, folder);
                values.put(Conversation.UPDATE_FOLDER_COLUMN,
                        Folder.getSerializedFolderString(targetFolders.values()));
                ops.add(mConversationListCursor.getOperationForConversation(target,
                        ConversationOperation.UPDATE, values));
            }
            if (mConversationListCursor != null) {
                mConversationListCursor.updateBulkValues(mContext, ops);
            }
            refreshConversationList();
            mSelectedSet.clear();
            return;
        }
    }

    private void handleDropInStarred(Folder folder) {
        final Collection<Conversation> conversations = mSelectedSet.values();
        // The conversation list deletes and performs the action if it exists.
        final ConversationListFragment convListFragment = getConversationListFragment();
        // There should always be a convlistfragment, or the user could not have
        // dragged/ dropped conversations.
        if (convListFragment != null) {
            LogUtils.d(LOG_TAG, "AAC.requestDelete: ListFragment is handling delete.");
            convListFragment.requestDelete(R.id.change_folder, conversations, mSelectedSet.views(),
                    new DroppedInStarredAction(conversations, mFolder));
            return;
        }
    }

    // When dragging conversations to the starred folder, remove from the
    // original folder and add a star
    private class DroppedInStarredAction implements DestructiveAction {
        private Collection<Conversation> mConversations;
        private Folder mInitialFolder;

        public DroppedInStarredAction(Collection<Conversation> conversations, Folder folder) {
            mConversations = conversations;
            mInitialFolder = folder;
        }

        @Override
        public void performAction() {
            ToastBarOperation undoOp = new ToastBarOperation(mConversations.size(),
                    R.id.change_folder, ToastBarOperation.UNDO, true);
            onUndoAvailable(undoOp);
            ArrayList<ConversationOperation> ops = new ArrayList<ConversationOperation>();
            ContentValues values = new ContentValues();
            for (Conversation target : mConversations) {
                HashMap<Uri, Folder> targetFolders = Folder.hashMapForFolders(target
                        .getRawFolders());
                target.localDeleteOnUpdate = true;
                targetFolders.remove(mInitialFolder.uri);
                values.put(Conversation.UPDATE_FOLDER_COLUMN,
                        Folder.getSerializedFolderString(targetFolders.values()));
                values.put(UIProvider.ConversationColumns.STARRED, true);
                ops.add(mConversationListCursor.getOperationForConversation(target,
                        ConversationOperation.UPDATE, values));
            }
            if (mConversationListCursor != null) {
                mConversationListCursor.updateBulkValues(mContext, ops);
            }
            refreshConversationList();
            mSelectedSet.clear();
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (mToastBar != null && !mToastBar.isEventInToastBar(event)) {
                hideOrRepositionToastBar(true);
            }
        }
    }

    protected abstract void hideOrRepositionToastBar(boolean animated);

    @Override
    public void onConversationSeen(Conversation conv) {
        mPagerController.onConversationSeen(conv);
    }

    @Override
    public boolean isInitialConversationLoading() {
        return mPagerController.isInitialConversationLoading();
    }

    private class ConversationListLoaderCallbacks implements
        LoaderManager.LoaderCallbacks<ConversationCursor> {

        @Override
        public Loader<ConversationCursor> onCreateLoader(int id, Bundle args) {
            Loader<ConversationCursor> result = new ConversationCursorLoader((Activity) mActivity,
                    mAccount, mFolder.conversationListUri, mFolder.name);
            return result;
        }

        @Override
        public void onLoadFinished(Loader<ConversationCursor> loader, ConversationCursor data) {
            LogUtils.d(LOG_TAG, "IN AAC.ConversationCursor.onLoadFinished, data=%s loader=%s",
                    data, loader);
            // Clear our all pending destructive actions before swapping the conversation cursor
            destroyPending(null);
            mConversationListCursor = data;
            mConversationListCursor.addListener(AbstractActivityController.this);

            mTracker.onCursorUpdated();
            mConversationListObservable.notifyChanged();

            final ConversationListFragment convList = getConversationListFragment();
            if (convList != null && convList.isVisible()) {
                // The conversation list is already listening to list changes and gets notified
                // in the mConversationListObservable.notifyChanged() line above. We only need to
                // check and inform the cursor of the change in visibility here.
                informCursorVisiblity(true);
            }
            perhapsShowFirstSearchResult();
        }

        @Override
        public void onLoaderReset(Loader<ConversationCursor> loader) {
            LogUtils.d(LOG_TAG, "IN AAC.ConversationCursor.onLoaderReset, data=%s loader=%s",
                    mConversationListCursor, loader);

            if (mConversationListCursor != null) {
                // Unregister the listener
                mConversationListCursor.removeListener(AbstractActivityController.this);
                mConversationListCursor = null;

                // Inform anyone who is interested about the change
                mTracker.onCursorUpdated();
                mConversationListObservable.notifyChanged();
            }
        }
    }

    /**
     * Updates controller state based on search results and shows first conversation if required.
     */
    private final void perhapsShowFirstSearchResult() {
        if (mCurrentConversation == null) {
            // Shown for search results in two-pane mode only.
            mHaveSearchResults = Intent.ACTION_SEARCH.equals(mActivity.getIntent().getAction())
                    && mConversationListCursor.getCount() > 0;
            if (!shouldShowFirstConversation()) {
                return;
            }
            mConversationListCursor.moveToPosition(0);
            final Conversation conv = new Conversation(mConversationListCursor);
            conv.position = 0;
            onConversationSelected(conv, true /* checkSafeToModifyFragments */);
        }
    }

    /**
     * Destroy the pending {@link DestructiveAction} till now and assign the given action as the
     * next destructive action..
     * @param nextAction the next destructive action to be performed. This can be null.
     */
    private final void destroyPending(DestructiveAction nextAction) {
        // If there is a pending action, perform that first.
        if (mPendingDestruction != null) {
            mPendingDestruction.performAction();
        }
        mPendingDestruction = nextAction;
    }

    /**
     * Register a destructive action with the controller. This performs the previous destructive
     * action as a side effect. This method is final because we don't want the child classes to
     * embellish this method any more.
     * @param action
     */
    private final void registerDestructiveAction(DestructiveAction action) {
        // TODO(viki): This is not a good idea. The best solution is for clients to request a
        // destructive action from the controller and for the controller to own the action. This is
        // a half-way solution while refactoring DestructiveAction.
        destroyPending(action);
        return;
    }

    @Override
    public final DestructiveAction getBatchAction(int action) {
        final DestructiveAction da = new ConversationAction(action, mSelectedSet.values(), true);
        registerDestructiveAction(da);
        return da;
    }

    @Override
    public final DestructiveAction getDeferredBatchAction(int action) {
        return getDeferredAction(action, mSelectedSet.values(), true);
    }

    /**
     * Get a destructive action for a menu action. This is a temporary method,
     * to control the profusion of {@link DestructiveAction} classes that are
     * created. Please do not copy this paradigm.
     * @param action the resource ID of the menu action: R.id.delete, for
     *            example
     * @param target the conversations to act upon.
     * @return a {@link DestructiveAction} that performs the specified action.
     */
    @Override
    public DestructiveAction getDeferredAction(int action, Collection<Conversation> target,
            boolean batch) {
        final DestructiveAction da = new ConversationAction(action, target, batch);
        return da;
    }

    /**
     * Class to change the folders that are assigned to a set of conversations. This is destructive
     * because the user can remove the current folder from the conversation, in which case it has
     * to be animated away from the current folder.
     */
    private class FolderDestruction implements DestructiveAction {
        private final Collection<Conversation> mTarget;
        private final ArrayList<FolderOperation> mFolderOps = new ArrayList<FolderOperation>();
        private final boolean mIsDestructive;
        /** Whether this destructive action has already been performed */
        private boolean mCompleted;
        private boolean mIsSelectedSet;
        private boolean mShowUndo;
        private int mAction;

        /**
         * Create a new folder destruction object to act on the given conversations.
         * @param target
         */
        private FolderDestruction(final Collection<Conversation> target,
                final Collection<FolderOperation> folders, boolean isDestructive, boolean isBatch,
                boolean showUndo, int action) {
            mTarget = ImmutableList.copyOf(target);
            mFolderOps.addAll(folders);
            mIsDestructive = isDestructive;
            mIsSelectedSet = isBatch;
            mShowUndo = showUndo;
            mAction = action;
        }

        @Override
        public void performAction() {
            if (isPerformed()) {
                return;
            }
            if (mIsDestructive && mShowUndo) {
                ToastBarOperation undoOp = new ToastBarOperation(mTarget.size(),
                        mAction, ToastBarOperation.UNDO, mIsSelectedSet);
                onUndoAvailable(undoOp);
            }
            // For each conversation, for each operation, add/ remove the
            // appropriate folders.
            ArrayList<String> updatedTargetFolders = new ArrayList<String>(mTarget.size());
            for (Conversation target : mTarget) {
                HashMap<Uri, Folder> targetFolders = Folder
                        .hashMapForFolders(target.getRawFolders());
                if (mIsDestructive) {
                    target.localDeleteOnUpdate = true;
                }
                for (FolderOperation op : mFolderOps) {
                    if (op.mAdd) {
                        targetFolders.put(op.mFolder.uri, op.mFolder);
                    } else {
                        targetFolders.remove(op.mFolder.uri);
                    }
                }
                updatedTargetFolders.add(Folder.getSerializedFolderString(targetFolders.values()));
            }
            if (mConversationListCursor != null) {
                mConversationListCursor.updateStrings(mContext, mTarget,
                        Conversation.UPDATE_FOLDER_COLUMN, updatedTargetFolders);
            }
            refreshConversationList();
            if (mIsSelectedSet) {
                mSelectedSet.clear();
            }
        }

        /**
         * Returns true if this action has been performed, false otherwise.
         *
         */
        private synchronized boolean isPerformed() {
            if (mCompleted) {
                return true;
            }
            mCompleted = true;
            return false;
        }
    }

    public final DestructiveAction getFolderChange(Collection<Conversation> target,
            Collection<FolderOperation> folders, boolean isDestructive, boolean isBatch,
            boolean showUndo) {
        final DestructiveAction da = getDeferredFolderChange(target, folders, isDestructive,
                isBatch, showUndo);
        registerDestructiveAction(da);
        return da;
    }

    public final DestructiveAction getDeferredFolderChange(Collection<Conversation> target,
            Collection<FolderOperation> folders, boolean isDestructive, boolean isBatch,
            boolean showUndo) {
        final DestructiveAction da = new FolderDestruction(target, folders, isDestructive, isBatch,
                showUndo, R.id.change_folder);
        return da;
    }

    @Override
    public final DestructiveAction getDeferredRemoveFolder(Collection<Conversation> target,
            Folder toRemove, boolean isDestructive, boolean isBatch,
            boolean showUndo) {
        Collection<FolderOperation> folderOps = new ArrayList<FolderOperation>();
        folderOps.add(new FolderOperation(toRemove, false));
        return new FolderDestruction(target, folderOps, isDestructive, isBatch,
                showUndo, R.id.remove_folder);
    }

    private final DestructiveAction getRemoveFolder(Collection<Conversation> target,
            Folder toRemove, boolean isDestructive, boolean isBatch, boolean showUndo) {
        DestructiveAction da = getDeferredRemoveFolder(target, toRemove, isDestructive, isBatch,
                showUndo);
        registerDestructiveAction(da);
        return da;
    }

    @Override
    public final void refreshConversationList() {
        final ConversationListFragment convList = getConversationListFragment();
        if (convList == null) {
            return;
        }
        convList.requestListRefresh();
    }

    protected final ActionClickedListener getUndoClickedListener(
            final AnimatedAdapter listAdapter) {
        return new ActionClickedListener() {
            @Override
            public void onActionClicked() {
                if (mAccount.undoUri != null) {
                    // NOTE: We might want undo to return the messages affected, in which case
                    // the resulting cursor might be interesting...
                    // TODO: Use UIProvider.SEQUENCE_QUERY_PARAMETER to indicate the set of
                    // commands to undo
                    if (mConversationListCursor != null) {
                        mConversationListCursor.undo(
                                mActivity.getActivityContext(), mAccount.undoUri);
                    }
                    if (listAdapter != null) {
                        listAdapter.setUndo(true);
                    }
                }
            }
        };
    }

    /**
     * Shows an error toast in the bottom when a folder was not fetched successfully.
     * @param folder the folder which could not be fetched.
     * @param replaceVisibleToast if true, this should replace any currently visible toast.
     */
    protected final void showErrorToast(final Folder folder, boolean replaceVisibleToast) {
        mToastBar.setConversationMode(false);

        final ActionClickedListener listener;
        final int actionTextResourceId;
        final int lastSyncResult = folder.lastSyncResult;
        switch (lastSyncResult & 0x0f) {
            case UIProvider.LastSyncResult.CONNECTION_ERROR:
                // The sync request that caused this failure.
                final int syncRequest = lastSyncResult >> 4;
                // Show: User explicitly pressed the refresh button and there is no connection
                // Show: The first time the user enters the app and there is no connection
                //       TODO(viki): Implement this.
                // Reference: http://b/7202801
                final boolean showToast = (syncRequest & UIProvider.SyncStatus.USER_REFRESH) != 0;
                // Don't show: Already in the app; user switches to a synced label
                // Don't show: In a live label and a background sync fails
                final boolean avoidToast = !showToast && (folder.syncWindow > 0
                        || (syncRequest & UIProvider.SyncStatus.BACKGROUND_SYNC) != 0);
                if (avoidToast) {
                    return;
                }
                listener = getRetryClickedListener(folder);
                actionTextResourceId = R.string.retry;
                break;
            case UIProvider.LastSyncResult.AUTH_ERROR:
                listener = getSignInClickedListener();
                actionTextResourceId = R.string.signin;
                break;
            case UIProvider.LastSyncResult.SECURITY_ERROR:
                return; // Currently we do nothing for security errors.
            case UIProvider.LastSyncResult.STORAGE_ERROR:
                listener = getStorageErrorClickedListener();
                actionTextResourceId = R.string.info;
                break;
            case UIProvider.LastSyncResult.INTERNAL_ERROR:
                listener = getInternalErrorClickedListener();
                actionTextResourceId = R.string.report;
                break;
            default:
                return;
        }
        mToastBar.show(listener,
                R.drawable.ic_alert_white,
                Utils.getSyncStatusText(mActivity.getActivityContext(), lastSyncResult),
                false, /* showActionIcon */
                actionTextResourceId,
                replaceVisibleToast,
                new ToastBarOperation(1, 0, ToastBarOperation.ERROR, false));
    }

    private ActionClickedListener getRetryClickedListener(final Folder folder) {
        return new ActionClickedListener() {
            @Override
            public void onActionClicked() {
                final Uri uri = folder.refreshUri;

                if (uri != null) {
                    startAsyncRefreshTask(uri);
                }
            }
        };
    }

    private ActionClickedListener getSignInClickedListener() {
        return new ActionClickedListener() {
            @Override
            public void onActionClicked() {
                promptUserForAuthentication(mAccount);
            }
        };
    }

    private ActionClickedListener getStorageErrorClickedListener() {
        return new ActionClickedListener() {
            @Override
            public void onActionClicked() {
                showStorageErrorDialog();
            }
        };
    }

    private void showStorageErrorDialog() {
        DialogFragment fragment = (DialogFragment)
                mFragmentManager.findFragmentByTag(SYNC_ERROR_DIALOG_FRAGMENT_TAG);
        if (fragment == null) {
            fragment = SyncErrorDialogFragment.newInstance();
        }
        fragment.show(mFragmentManager, SYNC_ERROR_DIALOG_FRAGMENT_TAG);
    }

    private ActionClickedListener getInternalErrorClickedListener() {
        return new ActionClickedListener() {
            @Override
            public void onActionClicked() {
                Utils.sendFeedback(
                        mActivity.getActivityContext(), mAccount, true /* reportingProblem */);
            }
        };
    }

    @Override
    public void onFooterViewErrorActionClick(Folder folder, int errorStatus) {
        Uri uri = null;
        switch (errorStatus) {
            case UIProvider.LastSyncResult.CONNECTION_ERROR:
                if (folder != null && folder.refreshUri != null) {
                    uri = folder.refreshUri;
                }
                break;
            case UIProvider.LastSyncResult.AUTH_ERROR:
                promptUserForAuthentication(mAccount);
                return;
            case UIProvider.LastSyncResult.SECURITY_ERROR:
                return; // Currently we do nothing for security errors.
            case UIProvider.LastSyncResult.STORAGE_ERROR:
                showStorageErrorDialog();
                return;
            case UIProvider.LastSyncResult.INTERNAL_ERROR:
                Utils.sendFeedback(
                        mActivity.getActivityContext(), mAccount, true /* reportingProblem */);
                return;
            default:
                return;
        }

        if (uri != null) {
            startAsyncRefreshTask(uri);
        }
    }

    @Override
    public void onFooterViewLoadMoreClick(Folder folder) {
        if (folder != null && folder.loadMoreUri != null) {
            startAsyncRefreshTask(folder.loadMoreUri);
        }
    }

    private void startAsyncRefreshTask(Uri uri) {
        if (mFolderSyncTask != null) {
            mFolderSyncTask.cancel(true);
        }
        mFolderSyncTask = new AsyncRefreshTask(mActivity.getActivityContext(), uri);
        mFolderSyncTask.execute();
    }

    private void promptUserForAuthentication(Account account) {
        if (account != null && !Utils.isEmpty(account.reauthenticationIntentUri)) {
            final Intent authenticationIntent =
                    new Intent(Intent.ACTION_VIEW, account.reauthenticationIntentUri);
            mActivity.startActivityForResult(authenticationIntent, REAUTHENTICATE_REQUEST_CODE);
        }
    }

    @Override
    public void onAccessibilityStateChanged() {
        // Clear the cache of objects.
        ConversationItemViewModel.onAccessibilityUpdated();
        // Re-render the list if it exists.
        ConversationListFragment frag = getConversationListFragment();
        if (frag != null) {
            AnimatedAdapter adapter = frag.getAnimatedAdapter();
            if (adapter != null) {
                adapter.notifyDataSetInvalidated();
            }
        }
    }
}
