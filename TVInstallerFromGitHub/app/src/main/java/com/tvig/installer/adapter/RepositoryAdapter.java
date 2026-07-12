package com.tvig.installer.adapter;

import android.graphics.Rect;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.tvig.installer.R;
import com.tvig.installer.data.RepositoryItem;

import java.util.ArrayList;
import java.util.List;

/**
 * TV-focused repository list. The card itself is the only focus target in each row so that
 * a single, unambiguous 3 dp focus ring is shown while navigating with the D-pad.
 */
public final class RepositoryAdapter
        extends RecyclerView.Adapter<RepositoryAdapter.RepositoryViewHolder> {

    public interface Listener {
        void onRepositoryClick(@NonNull RepositoryItem item);

        void onRepositoryFocus(@NonNull RepositoryItem item);
    }

    private final List<RepositoryItem> items = new ArrayList<>();
    private final Listener listener;

    public RepositoryAdapter(List<RepositoryItem> items, Listener listener) {
        this.listener = listener;
        setItems(items);
    }

    public void setItems(List<RepositoryItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RepositoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_repository, parent, false);
        return new RepositoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RepositoryViewHolder holder, int position) {
        RepositoryItem item = items.get(position);
        String repository = emptyIfNull(item.getRepository());
        String description = item.getDescription();
        boolean favorite = item.getSource() == RepositoryItem.Source.FAVORITE;

        holder.repoName.setText(repository);
        if (TextUtils.isEmpty(description) || TextUtils.isEmpty(description.trim())) {
            holder.repoDescription.setText(null);
            holder.repoDescription.setVisibility(View.GONE);
        } else {
            holder.repoDescription.setText(description.trim());
            holder.repoDescription.setVisibility(View.VISIBLE);
        }

        int badgeLabel = favorite ? R.string.favorite_badge : R.string.preset_badge;
        int badgeColor = favorite ? R.color.warning : R.color.focus;
        holder.sourceBadge.setText(badgeLabel);
        holder.sourceBadge.setTextColor(
                ContextCompat.getColor(holder.sourceBadge.getContext(), badgeColor));
        holder.sectionDivider.setVisibility(
                favorite && isFirstFavorite(position) ? View.VISIBLE : View.GONE);

        String sourceLabel = holder.sourceBadge.getContext().getString(badgeLabel);
        holder.repoCard.setContentDescription(holder.repoCard.getContext().getString(
                R.string.repository_item_content_description, repository, sourceLabel));

        holder.repoCard.setOnClickListener(v -> {
            int adapterPosition = holder.getAdapterPosition();
            if (listener != null && adapterPosition != RecyclerView.NO_POSITION) {
                listener.onRepositoryClick(items.get(adapterPosition));
            }
        });

        holder.repoCard.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                return;
            }
            int adapterPosition = holder.getAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) {
                return;
            }

            scrollFocusedCardIntoView(holder, adapterPosition);
            if (listener != null) {
                listener.onRepositoryFocus(items.get(adapterPosition));
            }
        });
    }

    private boolean isFirstFavorite(int position) {
        if (position < 0 || position >= items.size()
                || items.get(position).getSource() != RepositoryItem.Source.FAVORITE) {
            return false;
        }
        for (int i = 0; i < position; i++) {
            if (items.get(i).getSource() == RepositoryItem.Source.FAVORITE) {
                return false;
            }
        }
        return true;
    }

    private static void scrollFocusedCardIntoView(
            @NonNull RepositoryViewHolder holder, int adapterPosition) {
        holder.itemView.post(() -> {
            if (holder.getAdapterPosition() != adapterPosition) {
                return;
            }

            Rect cardBounds = new Rect(
                    0,
                    0,
                    holder.itemView.getWidth(),
                    holder.itemView.getHeight());
            holder.itemView.requestRectangleOnScreen(cardBounds, true);

            ViewParent parent = holder.itemView.getParent();
            if (parent instanceof RecyclerView) {
                ((RecyclerView) parent).scrollToPosition(adapterPosition);
            }
        });
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class RepositoryViewHolder extends RecyclerView.ViewHolder {
        final View repoCard;
        final TextView sectionDivider;
        final TextView repoName;
        final TextView repoDescription;
        final TextView sourceBadge;

        RepositoryViewHolder(@NonNull View itemView) {
            super(itemView);
            repoCard = itemView.findViewById(R.id.repoCard);
            sectionDivider = itemView.findViewById(R.id.sectionDivider);
            repoName = itemView.findViewById(R.id.repoName);
            repoDescription = itemView.findViewById(R.id.repoDescription);
            sourceBadge = itemView.findViewById(R.id.sourceBadge);

            repoCard.setFocusable(true);
            repoCard.setClickable(true);
            repoCard.setNextFocusRightId(R.id.browserHomeButton);
        }
    }
}
