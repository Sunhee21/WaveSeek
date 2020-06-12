package com.mostone.waveseek;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * ================================================
 * 作    者：JayGoo
 * 版    本：1.0.0
 * 创建日期：2017/7/21
 * 描    述: 绘制波浪曲线
 * ================================================
 */
public class WaveSeekBar extends View {
    private static final String TAG = "WaveLineView";

    private final int DEFAULT_SAMPLING_SIZE = 8;
    private final float DEFAULT_OFFSET_SPEED = 666F;
    private final int DEFAULT_SENSIBILITY = 5;

    //采样点的数量，越高越精细，但是高于一定限度肉眼很难分辨，越高绘制效率越低
    private int samplingSize;

    //控制向右偏移速度，越小偏移速度越快
    private float offsetSpeed;
    //平滑改变的音量值
    private float volume = 0;

    //用户设置的音量，[0,100]
    private int targetVolume = 50;

    //每次平滑改变的音量单元
    private float perVolume;

    //灵敏度，越大越灵敏[1,10]
    private int sensibility;



    //波浪线颜色
    private int lineColor;
    //进度颜色
    private int progressColor;
    //游标线颜色
    private int cursorColor;
    //粗线宽度
    private int thickLineWidth;
    //细线宽度
    private int fineLineWidth;

    private final Paint paint = new Paint();

    {
        //防抖动
        paint.setDither(true);
        //抗锯齿，降低分辨率，提高绘制效率
        paint.setAntiAlias(true);
    }

    private List<Path> paths = new ArrayList<>();

    {
        for (int i = 0; i < 4; i++) {
            paths.add(new Path());
        }
    }

    //不同函数曲线系数
    private float[] pathFuncs = {
            1.4f, 0.35f, 0.1f, -0.1f
    };

    //采样点X坐标
    private float[] samplingX;
    //采样点位置映射到[-2,2]之间
    private float[] mapX;
    private float[] fixMapX = {
            0.05f,
            0.10f,0.10f,
            0.05f,0.05f,
            0.10f,
            0.15f,0.15f,
            0.20f,0.25f,0.20f,
            0.15f,0.15f,
            0.10f,
            0.05f,0.05f,
            0.10f,
            0.15f,
            0.10f,
            0.05f,0.05f,
            0.10f,0.15f,0.20f,0.15f,0.10f,0.05f};
    //画布宽高
    private int width, height;
    //画布中心的高度
    private int centerHeight;
    //振幅
    private float amplitude;
    //存储衰变系数
    private SparseArray<Double> recessionFuncs = new SparseArray<>();
    //连线动画结束标记
    private boolean isPrepareLineAnimEnd = false;
    //连线动画位移
    private int lineAnimX = 0;
    //渐入动画结束标记
    private boolean isPrepareAlphaAnimEnd = false;
    //渐入动画百分比值[0,1f]
    private float prepareAlpha = 0f;
    //是否开启准备动画
    private boolean isOpenPrepareAnim = false;


    //小条集合
    private RectF[] rectF;
    //小条宽度
    private Float littleBarWidth;
    //小条最小高度
    private Float littleBarMinHeight;
    //小条间隙宽度
    private Float gapWidth;

    private int mPercent = 0;

    private Paint percentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WaveSeekBar(Context context) {
        this(context, null);
    }

    public WaveSeekBar(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WaveSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initAttr(attrs);
    }
    private float cursorPadding;
    private float cursorWidth;

    private void initAttr(AttributeSet attrs) {
        TypedArray t = getContext().obtainStyledAttributes(attrs, R.styleable.WaveSeekBar);
        lineColor = t.getColor(R.styleable.WaveSeekBar_wsbLineColor, Color.parseColor("#2ED184"));
        progressColor = t.getColor(R.styleable.WaveSeekBar_wsbProgressColor, Color.BLACK);
        cursorColor = t.getColor(R.styleable.WaveSeekBar_wsbCursorColor, Color.BLACK);
        offsetSpeed = DEFAULT_OFFSET_SPEED;
        sensibility = DEFAULT_SENSIBILITY;
        littleBarWidth = t.getDimension(R.styleable.WaveSeekBar_wsbLitterBarWidth, 5f);
        littleBarMinHeight = t.getDimension(R.styleable.WaveSeekBar_wsbLitterBarMinHeight, 5f);
        gapWidth = t.getDimension(R.styleable.WaveSeekBar_wsbGapWidth, 5f);

        cursorPadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,20, getContext().getResources().getDisplayMetrics());
        cursorWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,1, getContext().getResources().getDisplayMetrics());

        t.recycle();
        checkVolumeValue();
        checkSensibilityValue();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(lineColor);
        percentPaint.setColor(progressColor);
        percentPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));


    }


    public void setProgress(int mPercent) {
        this.mPercent = mPercent;
        postInvalidateDelayed(20);
    }

    private Long millisPassed = 0L;

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        millisPassed = System.currentTimeMillis() - startAt;
        float offset = millisPassed / offsetSpeed; //水平偏移

        if (null == samplingX || null == mapX || null == pathFuncs) {
            initDraw(canvas);
        }

        if (lineAnim(canvas)/*暂时都会是true*/) {

            softerChangeVolume(); //音量改变波动
            int layer = canvas.saveLayer(0f, 0f, getWidth(), getHeight(), paint, Canvas.ALL_SAVE_FLAG);
            //波形函数的值
            float curY;
            for (int i = 0; i < samplingSize; i++) {
                float x = samplingX[i];
                curY = (float) (amplitude * calcValue(fixMapX[i % fixMapX.length], offset));//curY 计算后基本固定值
                rectF[i].left = x;
                rectF[i].right = x + littleBarWidth;

                Float halfY = curY * pathFuncs[0] * 100 * 0.01f; //未开始播放时 最大波浪
                Log.d(TAG, String.format("绘制参数 offset:%f 、curY：%f、volume:%f", offset, curY, volume));

                Float top = centerHeight - halfY;
                Float bottom = centerHeight + halfY;
                Log.d(TAG, String.format("绘制参数 top-bottom：%f", top - bottom));
                rectF[i].top = ((top < bottom) ? top : bottom) - littleBarMinHeight / 2;
                rectF[i].bottom = ((top < bottom) ? bottom : top) + littleBarMinHeight / 2;
            }


            for (RectF rf : rectF) {
                canvas.drawRoundRect(rf, littleBarWidth, littleBarWidth, paint);
            }



            float progressX = ((getWidth()-getPaddingStart() -getPaddingEnd()) * mPercent/100f);
            canvas.drawRect(new RectF(getPaddingStart(), 0f, progressX + getPaddingStart() , getHeight()), percentPaint);
            canvas.restoreToCount(layer);

            if (isShowCursor || isPressedCursor){
                RectF cursorRectF = new RectF();
                cursorRectF.left = (((progressX - cursorWidth/2) < 0) ? 0 :progressX - cursorWidth/2) + getPaddingStart();
                cursorRectF.top = 0f;
                cursorRectF.right = (((progressX - cursorWidth/2) < 0) ? cursorWidth :progressX + cursorWidth/2)+ getPaddingStart();
                cursorRectF.bottom = getHeight();

                LinearGradient linearGradient = new LinearGradient(cursorRectF.left,cursorRectF.top,cursorRectF.right,cursorRectF.bottom,new int[]{Color.TRANSPARENT,cursorColor,Color.TRANSPARENT}, null,Shader.TileMode.CLAMP);
                cursorPaint.setShader(linearGradient);

                canvas.drawRect(cursorRectF,cursorPaint);
            }

        }


    }



    private float downX;
    private float downY;

    private boolean isShowCursor = false;
    private boolean isPressedCursor = false;

    public boolean isShowCursor() {
        return isShowCursor;
    }

    public void setShowCursor(boolean showCursor) {
        isShowCursor = showCursor;
    }

  // <editor-fold defaultstate="collapsed" desc="点击事件">


    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN){
            downX = event.getX();
            downY = event.getY();
            float cursorCenter= (getWidth()-getPaddingEnd() -getPaddingStart()) * mPercent/100f;
            float cursorLeft = cursorCenter - cursorPadding + getPaddingStart();
            float cursorRight = cursorCenter + cursorPadding + getPaddingStart();
            RectF rectF = new RectF(cursorLeft,0,cursorRight,getHeight());
            if (rectF.contains(downX,downY)){
                isPressedCursor = true;
            }
            return true;
        }else if (event.getAction() == MotionEvent.ACTION_MOVE){
            if (isPressedCursor){
                float moveX = event.getX();
                int percent = (int) (moveX/getWidth()*100);
                if (percent >= 100) mPercent = 100;
                else if (percent <= 0) mPercent = 0;
                else mPercent = percent;
                if (onProgressListener!=null){
                    onProgressListener.onProgress(mPercent);
                }
                postInvalidate();
                return true;
            }
        }else if (event.getAction() == MotionEvent.ACTION_UP|| event.getAction() == MotionEvent.ACTION_CANCEL){
            if (isPressedCursor){
                isPressedCursor = false;
                postInvalidate();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }



  // </editor-fold>

    private onProgressListener onProgressListener;

    public void setOnProgressListener(WaveSeekBar.onProgressListener onProgressListener) {
        this.onProgressListener = onProgressListener;
    }

    public interface onProgressListener{

        void onProgress(int progress);

    }


// <editor-fold defaultstate="collapsed" desc="绘制的">

    //检查音量是否合法
    private void checkVolumeValue() {
        if (targetVolume > 100) targetVolume = 100;
    }

    //检查灵敏度值是否合法
    private void checkSensibilityValue() {
        if (sensibility > 10) sensibility = 10;
        if (sensibility < 1) sensibility = 1;
    }

    /**
     * 使曲线振幅有较大改变时动画过渡自然
     */
    private void softerChangeVolume() {
        //这里减去perVolume是为了防止volume频繁在targetVolume上下抖动
        if (volume < targetVolume - perVolume) {
            volume += perVolume;
        } else if (volume > targetVolume + perVolume) {
            if (volume < perVolume * 2) {
                volume = perVolume * 2;
            } else {
                volume -= perVolume;
            }
        } else {
            volume = targetVolume;
        }

    }


    /**
     * 连线动画
     *
     * @param canvas
     * @return whether animation is end
     */
    private boolean lineAnim(Canvas canvas) {

        if (isPrepareLineAnimEnd || !isOpenPrepareAnim) return true;
        lineAnimX += width / 60;
        if (lineAnimX > width / 2) {
            isPrepareLineAnimEnd = true;

            return true;
        }

        return false;
    }




    private Long startAt = 0L;
    private boolean isStop = true;



    //初始化绘制参数
    private void initDraw(Canvas canvas) {
        width = canvas.getWidth() - getPaddingStart() - getPaddingEnd();
        height = canvas.getHeight();


        samplingSize = (int) ((width + gapWidth) / (gapWidth + littleBarWidth));

        centerHeight = height >> 1;

        //振幅为高度的1/4----------
        amplitude = height/2f / 4f;

        //适合View的理论最大音量值，和音量不属于同一概念
        perVolume = sensibility * 0.35f;

        //初始化采样点及映射
        //这里因为包括起点和终点，所以需要+1
        samplingX = new float[samplingSize ];
        mapX = new float[samplingSize];
        //确定采样点之间的间距
        float gap = gapWidth + littleBarWidth;
        //采样点的位置
        float x;
        rectF = new RectF[samplingSize];
        for (int i = 0; i < samplingSize; i++) {
            rectF[i] = new RectF();
            x = (i) * gap + getPaddingStart();
            samplingX[i] = x;
            //将采样点映射到[-2，2]
            mapX[i] = (x / (float) width) * 1 - 0.5f;
        }


    }

    /**
     * 计算波形函数中x对应的y值
     * <p>
     * 使用稀疏矩阵进行暂存计算好的衰减系数值，下次使用时直接查找，减少计算量
     *
     * @param mapX   换算到[-2,2]之间的x值
     * @param offset 偏移量
     * @return [-1, 1]
     */
    private double calcValue(float mapX, float offset) {
        int keyX = (int) (mapX * 1000);
        offset %= 2;
        double sinFunc = Math.sin(Math.PI * mapX - offset * Math.PI);
        double recessionFunc;
        if (recessionFuncs.indexOfKey(keyX) >= 0) {
            recessionFunc = recessionFuncs.get(keyX);
        } else {
            recessionFunc = 4 / (4 + Math.pow(mapX, 4));
            recessionFuncs.put(keyX, recessionFunc);
        }

        return sinFunc * recessionFunc;
    }



    public void setLineColor(int lineColor) {
        this.lineColor = lineColor;
    }



// </editor-fold>

}
