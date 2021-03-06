package gov.noaa.pmel.tmap.cleaner.jdo;

import javax.jdo.annotations.PersistenceCapable;
import javax.jdo.annotations.Persistent;

@PersistenceCapable
public class TimeAxis {
    
    @Persistent
    private String type = "t";
    
    @Persistent
    private String name;
    
    @Persistent
    private String boundaryRef;
    
    @Persistent
    private double minValue;
    
    @Persistent
    private double maxValue;
    
    @Persistent
    private long size;
    
    @Persistent
    private String positive;
    
    @Persistent
    private String isNumeric;
    
    @Persistent
    private String isContiguous;
    
    @Persistent
    private int elementSize;

    @Persistent
    private String calendar;
    
    @Persistent
    private String unitsString;
    
    @Persistent
    private String timeCoverageStart;
    
    @Persistent
    private String timeCoverageEnd;
    
    public String getTimeCoverageStart() {
        return timeCoverageStart;
    }

    public void setTimeCoverageStart(String timeCoverageStart) {
        this.timeCoverageStart = timeCoverageStart;
    }

    public String getTimeCoverageEnd() {
        return timeCoverageEnd;
    }

    public void setTimeCoverageEnd(String timeCoverageEnd) {
        this.timeCoverageEnd = timeCoverageEnd;
    }

    public String getUnitsString() {
        return unitsString;
    }

    public void setUnitsString(String unitsString) {
        this.unitsString = unitsString;
    }

    public String getCalendar() {
        return calendar;
    }

    public void setCalendar(String calendar) {
        this.calendar = calendar;
    }

    public void setNumeric(boolean isNumeric) {
        this.isNumeric = String.valueOf(isNumeric);
    }

    public void setContiguous(boolean isContiguous) {
        this.isContiguous = String.valueOf(isContiguous);
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getPositive() {
        return positive;
    }

    public void setPositive(String positive) {
        this.positive = positive;
    }

    public boolean isNumeric() {
        return Boolean.valueOf(isNumeric);
    }

    public void setIsNumeric(boolean isNumeric) {
        this.isNumeric = String.valueOf(isNumeric);
    }

    public boolean isContiguous() {
        return Boolean.valueOf(isContiguous);
    }

    public void setIsContiguous(boolean isContiguous) {
        this.isContiguous = String.valueOf(isContiguous);
    }

    public int getElementSize() {
        return elementSize;
    }

    public void setElementSize(int elementSize) {
        this.elementSize = elementSize;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBoundaryRef() {
        return boundaryRef;
    }

    public void setBoundaryRef(String boundaryRef) {
        this.boundaryRef = boundaryRef;
    }

    public double getMinValue() {
        return minValue;
    }

    public void setMinValue(double minValue) {
        this.minValue = minValue;
    }

    public double getMaxValue() {
        return maxValue;
    }

    public void setMaxValue(double maxValue) {
        this.maxValue = maxValue;
    }
    @Override
    public String toString() {
        String sig = this.name;
        if ( this.calendar != null ) sig = sig+this.calendar;
        return sig;
    }
}
