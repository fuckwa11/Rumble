/*
 * Copyright (C) 2014 Disrupted Systems
 *
 * This file is part of Rumble.
 *
 * Rumble is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Rumble is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rumble.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.disrupted.rumble.userinterface.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.disrupted.rumble.R;
import org.disrupted.rumble.database.objects.Contact;
import org.disrupted.rumble.userinterface.events.UserSetHashTagInterest;
import org.disrupted.rumble.userinterface.fragments.FragmentStatusList;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import de.greenrobot.event.EventBus;

/**
 * @author Marlinski
 */
public class FilterListAdapter extends BaseAdapter {

    private static final String TAG = "FilterListAdapter";

    public static class FilterEntry {
        public String  filter;
        public FragmentStatusList.OnFilterClick filterClick;
    }

    Context context;
    LayoutInflater inflater;
    List<FilterEntry> filterList;

    public FilterListAdapter(Context context) {
        this.context = context;
        this.inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        this.filterList = new LinkedList<FilterEntry>();
    }

    @Override
    public View getView(int i, View view, ViewGroup viewGroup) {
        View filterView = inflater.inflate(R.layout.filter_item, null, true);
        TextView filterTextView       = (TextView) filterView.findViewById(R.id.filter_text);
        LinearLayout outerFilterView  = (LinearLayout) filterView.findViewById(R.id.filter_outer_text);
        TextView subscriptionTextView = (TextView) filterView.findViewById(R.id.subscription_button);

        FilterEntry entry = filterList.get(i);
        filterTextView.setText(entry.filter);

        if(entry.filterClick != null)
            outerFilterView.setOnClickListener(new OnAdapterFilterClick(entry.filter, entry.filterClick));

        boolean isInterested;
        Integer interest = Contact.getLocalContact().getHashtagInterests().get(entry.filter);
        isInterested = ((interest != null) && (interest > 0));
        if(isInterested) {
            subscriptionTextView.setText(R.string.filter_subscribed);
            subscriptionTextView.setTextColor(context.getResources().getColor(R.color.white));
            subscriptionTextView.setBackgroundColor(context.getResources().getColor(R.color.green));
        } else {
            subscriptionTextView.setText(R.string.filter_not_subscribed);
            subscriptionTextView.setTextColor(context.getResources().getColor(R.color.black));
            subscriptionTextView.setBackgroundColor(context.getResources().getColor(R.color.white));
        }
        subscriptionTextView.setOnClickListener(new OnAdapterSubscriptionClick(entry.filter, isInterested, onSubscriptionClick));

        return filterView;
    }

    // todo should have its own object
    OnSubscriptionClick onSubscriptionClick = new OnSubscriptionClick() {
        @Override
        public void onClick(String filter, boolean interested) {
            if(interested)
                EventBus.getDefault().post(new UserSetHashTagInterest(filter, Contact.MIN_INTEREST_TAG_VALUE));
            else
                EventBus.getDefault().post(new UserSetHashTagInterest(filter, Contact.MAX_INTEREST_TAG_VALUE));
        }
    };

    @Override
    public long getItemId(int i) {
        if(filterList == null)
            return 0;
        if(filterList.isEmpty())
            return 0;
        return i;
    }

    @Override
    public Object getItem(int i) {
        return filterList.get(i);
    }

    @Override
    public int getCount() {
        if(filterList != null)
            return filterList.size();
        return 0;
    }

    public void deleteFilter(String filter) {
        Iterator<FilterEntry> it = filterList.iterator();
        while(it.hasNext()) {
            if(it.next().filter.equals(filter)) {
                it.remove();
                return;
            }
        }
    }

    public boolean addFilter(FilterEntry filter) {
        for(FilterEntry entry : filterList) {
            if((entry.filter).toLowerCase().equals(filter.filter.toLowerCase()))
                return false;
        }
        filterList.add(filter);
        return true;
    }

    public Set<String> getFilterList() {
        Set<String> filters = new HashSet<String>();
        for(FilterEntry entry : filterList) {
            filters.add(entry.filter);
        }
        return filters;
    }

    public class OnAdapterFilterClick implements View.OnClickListener {

        private String filter;
        private FragmentStatusList.OnFilterClick callback;

        public OnAdapterFilterClick(String filter, FragmentStatusList.OnFilterClick callback) {
            this.filter = filter;
            this.callback = callback;
        }

        @Override
        public void onClick(View view) {
            callback.onClick(filter);
        }
    }

    private interface OnSubscriptionClick {
        public void onClick(String filter, boolean interested);
    }
    private class OnAdapterSubscriptionClick implements View.OnClickListener {

        private String filter;
        private boolean interested;
        private FilterListAdapter.OnSubscriptionClick callback;

        public OnAdapterSubscriptionClick(String filter, boolean interested, OnSubscriptionClick callback) {
            this.filter = filter;
            this.interested = interested;
            this.callback = callback;
        }

        @Override
        public void onClick(View view) {
            callback.onClick(filter, interested);
        }
    }


}
