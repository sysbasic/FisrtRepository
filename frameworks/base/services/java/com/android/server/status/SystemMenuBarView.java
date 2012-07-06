package com.android.server.status;

import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.internal.R;

public class SystemMenuBarView extends LinearLayout {
    private Context mContext;
    private int mOrientation = -1;
    private int mCount = 0;
    private ImageView iv1, iv2, iv3, iv4;
    
    public SystemMenuBarView(Context context) {
        super(context);
        // TODO Auto-generated constructor stub
        this.mContext = context;
    }
    
    public SystemMenuBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // TODO Auto-generated constructor stub
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        iv1 = (ImageView)findViewById(R.id.panel1);
        iv2 = (ImageView)findViewById(R.id.panel2);
        iv3 = (ImageView)findViewById(R.id.panel3);            
        iv4 = (ImageView)findViewById(R.id.panel4);

    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        // TODO Auto-generated method stub
        if (mOrientation != newConfig.orientation){
            
            //Log.i("orie", "mCount:" + mCount++ + "   mOrientation: " + mOrientation + "      newConfig.orientation: " + newConfig.orientation);
            mOrientation = newConfig.orientation;
            if (newConfig.orientation==Configuration.ORIENTATION_LANDSCAPE){
                Log.i("orie", "land");
                setOrientation(LinearLayout.VERTICAL);
                iv1.setImageResource(R.drawable.panel_back);
                iv2.setImageResource(R.drawable.panel_search);
                iv3.setImageResource(R.drawable.panel_menu);
                iv4.setImageResource(R.drawable.panel_home);
            }else{
                Log.i("orie", "port");
                setOrientation(LinearLayout.HORIZONTAL);
                iv1.setImageResource(R.drawable.panel_home);
                iv2.setImageResource(R.drawable.panel_menu);
                iv3.setImageResource(R.drawable.panel_search);
                iv4.setImageResource(R.drawable.panel_back);
            }
        }
        Log.i("orie", "executed");
        super.onConfigurationChanged(newConfig);
    } 
}