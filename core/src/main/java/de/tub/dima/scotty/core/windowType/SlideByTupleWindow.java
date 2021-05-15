package de.tub.dima.scotty.core.windowType;

import de.tub.dima.scotty.core.WindowCollector;
import de.tub.dima.scotty.core.windowType.windowContext.WindowContext;

import java.util.ArrayList;
import java.util.Collections;

public class SlideByTupleWindow implements ForwardContextAware {

    private final WindowMeasure measure;
    private final long size;
    private final long slide;

    /**
     * the measure of the Slide-by-tuple Window is time
     * @param size size of the SlideByTuple window in time
     * @param slide window slide step in tuple counts
     */
    public SlideByTupleWindow(long size, long slide){
        this.measure = WindowMeasure.Time;
        this.size = size;
        this.slide = slide;
    }

    public long getSize() {
        return size;
    }

    public long getSlide() {
        return slide;
    }

    @Override
    public WindowMeasure getWindowMeasure() {
        return measure;
    }

    @Override
    public SlideByTupleContext createContext() {
        return new SlideByTupleContext();
    }

    public class SlideByTupleContext extends WindowContext<Object> {

        private long nextStart = 0; //start count of next window
        private long count = 0; //tuple-counter
        private ArrayList<Long> timestamps = new ArrayList<Long>(); //holds timestamps of tuples for out-of-order processing

        @Override
        public ActiveWindow updateContext(Object tuple, long position){

            if (hasActiveWindows()) {
                //append first window
                addNewWindow(0, position, position + size);
                count++;
                nextStart += getSlide();
                timestamps.add(position);
                return getWindow(0);
            }

            if (position >= timestamps.get(timestamps.size() -1)) {
                //processes in-order tuples
                timestamps.add(position);
                int windowIndex = getWindowIndex(position);

                if (windowIndex == -1) {
                    addNewWindow(0, position, position + size);
                    count++;
                    nextStart += getSlide();

                } else {
                    if (count == nextStart) { //new window starts
                        count++;
                        nextStart += getSlide();
                        return addNewWindow(windowIndex + 1, position, position + size);

                    } else {
                        ActiveWindow w = getWindow(windowIndex);
                        if (w.getEnd() > position) { //append to active window
                            count++;
                            return w;
                        } else {
                        /* Tuple, which does not belong to current window and where count != nextStart,
                        does not get included in any window instance */
                            count++;
                        }
                    }
                }
            } else {
                //processes out-of-order tuples

                timestamps.add(position);
                Collections.sort(timestamps);

                int windowIndex = getWindowIndex(position);

                if(slide == 1){ // slide == 1: add new window for every new tuple
                    count ++;
                    nextStart += getSlide();
                    return addNewWindow(windowIndex+1, position, position + getSize());
                }

                if (windowIndex+1 <= (numberOfActiveWindows()-1)) {
                    //windows after the tuple exist, they have to be shifted
                    //beginning from the next window, to which the tuples does not belong
                    for(int i = windowIndex+1; i <= (numberOfActiveWindows()-1); i++){
                        ActiveWindow w = getWindow(i);
                        int index = timestamps.indexOf(w.getStart());
                        long timestampBefore = (long) timestamps.get(index-1);

                        if(position == timestampBefore){ //start of window has to be shifted to current tuple
                            // shift start of window and modify slice start, otherwise insertion into wrong slice
                            shiftStart(w,timestampBefore);
                        }else{
                            // shift start of window, split slice if necessary
                            shiftStartDontModify(w,timestampBefore);
                            splitSlice(timestampBefore);
                        }

                        shiftEnd(w, timestampBefore + size);
                        splitSlice(timestampBefore+size);
                    }
                    count++;
                    return null;

                } else {
                    //no subsequent windows exist, out-of-order tuple may start a new window
                    long lateCount = timestamps.indexOf(position); //count of current tuple
                    long windowStart = getWindow(windowIndex).getStart();
                    int indexOfStart = timestamps.indexOf(windowStart); //tuple count on position of start of last window
                    long lateNextStart = indexOfStart + getSlide(); //tuple count of next start

                    if (lateCount==lateNextStart) { // tuple starts a new window
                        nextStart += getSlide(); //update next start
                        addNewWindow(windowIndex + 1, position, position + size);
                    }
                    count++;
                }
            }
            return null;
        }

        public int getWindowIndex(long position) {
            //returns newest window
            int i = numberOfActiveWindows()-1;
            for (; i >= 0 ; i--) {
                ActiveWindow p = getWindow(i);
                if (p.getStart() <= position) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public long assignNextWindowStart(long position) {
            if (slide > 1) {
                if (count == nextStart) {
                    // new Window starts, append new Slice
                    return position;
                } else {
                    // determine if new slice is needed
                    int windowIndex = getWindowIndex(position);
                    if(windowIndex == -1){
                        return position;
                    }
                    ActiveWindow w = getWindow(windowIndex);
                    if (windowIndex == 0) { // first window: all tuples belong to the first slice
                        return w.getEnd();
                    } else {
                        ActiveWindow wBefore = getWindow(windowIndex - 1);
                        if (wBefore.getEnd() <= position) {
                            //tuple before does not belong to window before -> append current value to slice
                            return w.getEnd();
                        } else {
                            //tuple before also belongs to the window before or is outlier -> new slice
                            return wBefore.getEnd();
                        }
                    }
                }
            } else { //append one slice for each tuple for slide == 1
                return position;
            }
        }

        @Override
        public void triggerWindows(WindowCollector aggregateWindows, long lastWatermark, long currentWatermark) {
            ActiveWindow w = getWindow(0);
            while (w.getEnd() <= currentWatermark) {
                aggregateWindows.trigger(w.getStart(), w.getEnd() , measure);
                removeWindow(0);
                if (hasActiveWindows())
                    return;
                w = getWindow(0);
            }
        }
    }

    @Override
    public String toString() {
        return "SlideByTupleWindow{" +
                "measure=" + measure +
                ", size=" + size +
                ", slide=" + slide +
                '}';
    }
}
