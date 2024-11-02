package com.hoejmoseit.wingman.wingmanapp.database;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hoejmoseit.wingman.R;

import java.util.List;

public class LanguageAdapter extends RecyclerView.Adapter<LanguageAdapter.ViewHolder> {

		private List<String> items;

    public LanguageAdapter(List<String> items ) {
			this.items = items;
		}

		// ... (ViewHolder and other adapter methods) ...

		public void addItem(String item) {
			items.add(item);
			notifyItemInserted(items.size() - 1); // Notify adapter of the new item
		}

	@NonNull
	@Override
	public LanguageAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.language_item, parent, false);

		return new LanguageAdapter.ViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
		String item = items.get(position);
		holder.titleTextView.setText(item);
		holder.iconImageView.setImageResource(R.drawable.check);
	}


	@Override
	public int getItemCount() {
		return 0;
	}

	public static class ViewHolder extends RecyclerView.ViewHolder {
		public TextView titleTextView;

		public ImageView iconImageView;

		public ViewHolder(View itemView) {
			super(itemView);
			titleTextView = itemView.findViewById(R.id.language_name);
			iconImageView = itemView.findViewById(R.id.checkmark_icon);
		}
	}

}