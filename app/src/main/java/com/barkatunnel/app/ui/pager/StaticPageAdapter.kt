package com.barkatunnel.app.ui.pager

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView

class StaticPageAdapter(
    private val pages: List<View>
) : RecyclerView.Adapter<StaticPageAdapter.PageViewHolder>() {

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = pages.size

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val container = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        return PageViewHolder(container)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = pages[position]
        (page.parent as? ViewGroup)?.removeView(page)
        holder.container.removeAllViews()
        holder.container.addView(
            page,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    class PageViewHolder(
        val container: FrameLayout
    ) : RecyclerView.ViewHolder(container)
}
