package com.bigsinger.tvinstaller.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bigsinger.tvinstaller.R;
import com.bigsinger.tvinstaller.data.SmbEntry;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {
    public interface Listener {
        void onEntryClick(SmbEntry entry);
    }

    private final List<SmbEntry> entries;
    private final Listener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    public FileAdapter(List<SmbEntry> entries, Listener listener) {
        this.entries = entries;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SmbEntry entry = entries.get(position);
        holder.typeText.setText(entry.isDirectory() ? "DIR" : "APK");
        holder.nameText.setText(stripTrailingSlash(entry.getName()));
        holder.detailText.setText(entry.isDirectory() ? "文件夹" : detail(entry));
        holder.actionText.setText(entry.isDirectory() ? "打开" : "安装");
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onEntryClick(entry);
            }
        });
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    private String detail(SmbEntry entry) {
        String time = entry.getModified() > 0
                ? dateFormat.format(new Date(entry.getModified()))
                : "未知时间";
        return time + " · " + formatSize(entry.getSize());
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) {
            return "未知大小";
        }
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit]);
    }

    private String stripTrailingSlash(String value) {
        if (value != null && value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView typeText;
        final TextView nameText;
        final TextView detailText;
        final TextView actionText;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            typeText = itemView.findViewById(R.id.typeText);
            nameText = itemView.findViewById(R.id.nameText);
            detailText = itemView.findViewById(R.id.detailText);
            actionText = itemView.findViewById(R.id.actionText);
        }
    }
}

