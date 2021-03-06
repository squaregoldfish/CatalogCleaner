package gov.noaa.pmel.tmap.cleaner.crawler;

import gov.noaa.pmel.tmap.addxml.ADDXMLProcessor;
import gov.noaa.pmel.tmap.addxml.AxisBean;
import gov.noaa.pmel.tmap.cleaner.jdo.Catalog;
import gov.noaa.pmel.tmap.cleaner.jdo.CatalogComment;
import gov.noaa.pmel.tmap.cleaner.jdo.DoubleAttribute;
import gov.noaa.pmel.tmap.cleaner.jdo.FloatAttribute;
import gov.noaa.pmel.tmap.cleaner.jdo.GeoAxis;
import gov.noaa.pmel.tmap.cleaner.jdo.IntAttribute;
import gov.noaa.pmel.tmap.cleaner.jdo.LeafDataset;
import gov.noaa.pmel.tmap.cleaner.jdo.LeafNodeReference;
import gov.noaa.pmel.tmap.cleaner.jdo.LongAttribute;
import gov.noaa.pmel.tmap.cleaner.jdo.NetCDFVariable;
import gov.noaa.pmel.tmap.cleaner.jdo.PersistenceHelper;
import gov.noaa.pmel.tmap.cleaner.jdo.ShortAttribute;
import gov.noaa.pmel.tmap.cleaner.jdo.StringAttribute;
import gov.noaa.pmel.tmap.cleaner.jdo.TimeAxis;
import gov.noaa.pmel.tmap.cleaner.jdo.VerticalAxis;
import gov.noaa.pmel.tmap.cleaner.jdo.LeafNodeReference.DataCrawlStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;

import javax.jdo.JDOHelper;
import javax.jdo.PersistenceManager;
import javax.jdo.Transaction;

import org.datanucleus.api.jdo.JDOPersistenceManagerFactory;
import org.joda.time.DateTime;

import ucar.ma2.DataType;
import ucar.nc2.Attribute;
import ucar.nc2.constants.FeatureType;
import ucar.nc2.dataset.CoordinateAxis;
import ucar.nc2.dataset.CoordinateAxis1D;
import ucar.nc2.dataset.CoordinateAxis1DTime;
import ucar.nc2.dataset.CoordinateAxis2D;
import ucar.nc2.dt.GridDatatype;
import ucar.nc2.dt.grid.GridCoordSys;
import ucar.nc2.dt.grid.GridDataset;
import ucar.nc2.ft.FeatureDatasetFactoryManager;
import ucar.nc2.units.DateRange;
import ucar.nc2.util.CancelTask;
import ucar.unidata.geoloc.LatLonRect;
import ucar.unidata.geoloc.Projection;
import ucar.unidata.geoloc.projection.AlbersEqualArea;
import ucar.unidata.geoloc.projection.FlatEarth;
import ucar.unidata.geoloc.projection.LambertAzimuthalEqualArea;
import ucar.unidata.geoloc.projection.LambertConformal;
import ucar.unidata.geoloc.projection.LatLonProjection;
import ucar.unidata.geoloc.projection.Mercator;
import ucar.unidata.geoloc.projection.Orthographic;
import ucar.unidata.geoloc.projection.ProjectionAdapter;
import ucar.unidata.geoloc.projection.RotatedLatLon;
import ucar.unidata.geoloc.projection.RotatedPole;
import ucar.unidata.geoloc.projection.Stereographic;
import ucar.unidata.geoloc.projection.TransverseMercator;
import ucar.unidata.geoloc.projection.UtmProjection;
import ucar.unidata.geoloc.projection.VerticalPerspectiveView;

public abstract class DataCrawl implements Callable<String> {
    String parent;
    String url;
    String leafurl;
    JDOPersistenceManagerFactory pmf;
    PersistenceHelper helper;
    boolean force = false;
    
    public DataCrawl(JDOPersistenceManagerFactory pmf, String parent, String url, String leafurl, boolean force) {
        super();
        this.pmf = pmf;
        this.parent = parent;
        this.url = url;
        this.leafurl = leafurl;
        this.force = force;
       
    }
    
    @Override
    public abstract String call() throws Exception; 
    
    protected LeafDataset crawlLeafNode(String parent, String url) throws Exception {
        LeafDataset leaf = helper.getLeafDataset(url);
        if ( leaf == null || force ) {
            if ( leaf == null ) {
                leaf = new LeafDataset(url);
                helper.save(leaf);
            }
            crawlLeafNode(leaf, parent, url);
        } else {
            List<NetCDFVariable> variables = leaf.getVariables();
            if ( variables == null || variables.size() == 0 ) {
                crawlLeafNode(leaf, parent, url);
            } else {
                System.out.println("Already crawled: "+url);
            }
        }
        return leaf;
    }
    protected GridDataset readSource(String url) throws Exception {
        final CatalogComment cancelMessage = new CatalogComment();

        GridDataset gridDs = null;
            CancelTask cancelTask = new CancelTask() {
                long starttime = System.currentTimeMillis();
                public boolean isCancel() {
                    if(System.currentTimeMillis() > starttime + 2*60*1000) // currently set to 2 minutes
                        return true;
                    return false;
                }
                public void setError(String err) {
                    cancelMessage.setComment("CANCEL TASK _ taking too long: " + err);
                }
				@Override
				public void setProgress(String arg0, int arg1) {
					// no need
					
				}
            };
            gridDs = (GridDataset) FeatureDatasetFactoryManager.open(FeatureType.GRID, url, cancelTask, null);
            
            if ( gridDs == null ) {
                gridDs = (GridDataset) FeatureDatasetFactoryManager.open(FeatureType.FMRC, url, cancelTask, null);
            } 
            if ( gridDs != null ) {
                List<GridDatatype> grids = gridDs.getGrids();
                if ( grids.size() <= 0 ) {
                    gridDs = (GridDataset) FeatureDatasetFactoryManager.open(FeatureType.FMRC, url, cancelTask, null);
                }
            }
            if ( cancelMessage.getComment() != null ) {
                throw new Exception(cancelMessage.getComment());
            }
        
        return gridDs;
    }
    protected void updateLeafNodeTime(LeafDataset leaf) {
        List<NetCDFVariable> vars = leaf.getVariables();
        try {
            GridDataset gridDs = readSource(leafurl);
            if ( gridDs != null ) {
                List<GridDatatype> grids = gridDs.getGrids();
                for(int i = 0; i<grids.size(); i++){
                    GridDatatype grid = grids.get(i);
                    GridCoordSys gcs = (GridCoordSys) grid.getCoordinateSystem();
                    String name = grid.getName();
                    NetCDFVariable var = findVariable(name, vars);
                    if ( var != null ) {
                        if ( gcs.hasTimeAxis() ) {
                            TimeAxis timeAxis = var.getTimeAxis();
                            CoordinateAxis tAxis = gcs.getTimeAxis();
                            double minvalue;
                            double maxvalue;
                            try{
                                maxvalue = tAxis.getMaxValue();
                                minvalue = tAxis.getMinValue();
                                timeAxis.setMinValue(minvalue);
                                timeAxis.setMaxValue(maxvalue);
                                String start = timeAxis.getTimeCoverageStart();
                                String end = timeAxis.getTimeCoverageEnd();
                                long size = timeAxis.getSize();
                                DateRange range = gcs.getDateRange();
                                setTime(timeAxis, tAxis, range);
                                String nstart = timeAxis.getTimeCoverageStart();;
                                String nend = timeAxis.getTimeCoverageEnd();
                                long nsize = timeAxis.getSize();
                                if ( nstart != null && nend != null ) {
                                    if ( !nstart.equals(start) || !nend.equals(end) ) {
                                        System.out.println("Time change for "+leafurl+"==>    Start: "+start+" now "+nstart+" End: "+end+" now "+nend+" Size: "+size+" now "+nsize);
                                        List<Catalog> catalogs = helper.getCatalogThatContainsDataset(leafurl);
                                        if ( catalogs != null ) {
                                            for (Iterator iterator = catalogs.iterator(); iterator.hasNext();) {
                                                Catalog catalog = (Catalog) iterator.next();
                                                System.out.println("Update WAF for "+catalog.getUrl());
                                            }
                                        }
                                    }
                                }
                            } catch ( Exception e) {
                                // This is not a double valued axis.  We're skipping it for now.  :-)
                                System.err.println("Time not updated: "+e.getLocalizedMessage());
                            }
                        }
                    } 
                }
            }
        } catch (Exception e) {
            System.err.println("netCDF variable time update of "+leaf.getUrl()+" halted with "+e);
        }
        catch (Error e) {
            System.err.println("netCDF variable time updae of "+leaf.getUrl()+" halted with "+e);
        } 
    }
    protected NetCDFVariable findVariable(String name, List<NetCDFVariable> vars) {
        for ( Iterator varsIt = vars.iterator(); varsIt.hasNext(); ) {
            NetCDFVariable netCDFVariable = (NetCDFVariable) varsIt.next();
            if ( netCDFVariable.getName().equals(name) ) {
                return netCDFVariable;
            }
        }
        return null;
    }
    protected void crawlLeafNode(LeafDataset leaf, String parent, String url ) {
        try {
            if ( url.endsWith("_fmrc.ncd") ) {
                leaf.setVariables(new ArrayList<NetCDFVariable>());
                CatalogComment comment = new CatalogComment();
                comment.setComment("This data set appears to be an FMRC with run and forecast times.  We aren't handling those yet.");
                leaf.setComment(comment);
                return;
            }
            GridDataset gridDs = readSource(url);
            if ( gridDs == null ) {
                CatalogComment comment = new CatalogComment();
                comment.setComment("Unable to find any grids in this data set using the CF conventions.");
                leaf.setComment(comment);
                return;
            }
            Attribute history = gridDs.findGlobalAttributeIgnoreCase("history");
            boolean fmrc = false;
            if ( history != null ) {
                String value = history.getStringValue();
                if ( value != null ) {
                    if ( value.contains("FMRC 2D Dataset") ) {
                        fmrc = true;
                        leaf.setVariables(new ArrayList<NetCDFVariable>());
                    }
                }
            }
            if ( gridDs != null && !fmrc ) {
                List<GridDatatype> grids = gridDs.getGrids();
                List<NetCDFVariable> vars = new ArrayList<NetCDFVariable>();
                List<NetCDFVariable> badvars = new ArrayList<NetCDFVariable>();
                for(int i = 0; i<grids.size(); i++){
                    NetCDFVariable var = crawlNewVariable(grids.get(i));
                    if ( var != null && var.getError().equals("none") ) {
                        vars.add(var);
                    } else {
                        badvars.add(var);
                    }
                }
                leaf.setVariables(vars);
                leaf.setBadVariables(badvars);
            }
        } catch (Exception e) {
            System.err.println("netCDF variable crawling of "+leaf.getUrl()+" halted with "+e);
        }
        catch (Error e) {
            System.err.println("netCDF variable crawling of "+leaf.getUrl()+" halted with "+e);
        }
    }
    private NetCDFVariable crawlNewVariable(GridDatatype grid) {
        NetCDFVariable var = new NetCDFVariable();
        var.setDescription(grid.getDescription());
        var.setInfo(grid.getInfo());
        var.setName(grid.getName());
        var.setRank(grid.getRank());
        var.setUnitsString(grid.getUnitsString());
        var.setDataType(grid.getDataType().toString());
        var.setHasMissingData(grid.hasMissingData());
        List<Attribute> attributes = grid.getAttributes();
        
        List<DoubleAttribute> doubleAttributes = new ArrayList<DoubleAttribute>();
        List<FloatAttribute> floatAttributes = new ArrayList<FloatAttribute>();
        List<ShortAttribute> shortAttributes = new ArrayList<ShortAttribute>();
        List<IntAttribute> intAttributes = new ArrayList<IntAttribute>();
        List<LongAttribute> longAttributes = new ArrayList<LongAttribute>();
        List<StringAttribute> stringAttributes = new ArrayList<StringAttribute>();

        for(int i=0; i<attributes.size(); i++){
            
            Attribute attribute = attributes.get(i);
            // Capture a couple of special attributes
            if(attribute.getName().toLowerCase().equals("standard_name"))
                var.setStandardName(attribute.getStringValue());
            else if(attribute.getName().toLowerCase().equals("long_name"))
                var.setLongName(attribute.getStringValue());
           
            // Capture all attributes...
            if ( attribute.getDataType() == DataType.DOUBLE) {
                List<Double> v = new ArrayList<Double>();
                for (int d = 0; d < attribute.getLength(); d++) {
                    v.add(new Double(attribute.getNumericValue(d).doubleValue()));
                }
                doubleAttributes.add(new DoubleAttribute(attribute.getName(), v));
            } else if ( attribute.getDataType() == DataType.FLOAT ) {
                List<Float> v = new ArrayList<Float>();
                for (int d = 0; d < attribute.getLength(); d++) {
                    v.add(new Float(attribute.getNumericValue(d).floatValue()));
                }
                floatAttributes.add(new FloatAttribute(attribute.getName(), v));
            } else if ( attribute.getDataType() == DataType.SHORT ) {
                List<Short> v = new ArrayList<Short>();
                for (int d = 0; d < attribute.getLength(); d++) {
                    v.add(new Short(attribute.getNumericValue(d).shortValue()));
                }
                shortAttributes.add(new ShortAttribute(attribute.getName(), v));
                
            } else if ( attribute.getDataType() == DataType.INT ) {
                List<Integer> v = new ArrayList<Integer>();
                for (int d = 0; d < attribute.getLength(); d++) {
                    v.add(new Integer(attribute.getNumericValue(d).intValue()));
                }
                intAttributes.add(new IntAttribute(attribute.getName(), v));
            } else if ( attribute.getDataType() == DataType.LONG ) {
                List<Long> v = new ArrayList<Long>();
                for (int d = 0; d < attribute.getLength(); d++) {
                    v.add(new Long(attribute.getNumericValue(d).longValue()));
                }
                longAttributes.add(new LongAttribute(attribute.getName(), v));
            } else if ( attribute.getDataType() == DataType.STRING ) {
                List<String> v = new ArrayList<String>();
                for ( int d = 0; d < attribute.getLength(); d++ ) {
                    v.add(new String(attribute.getStringValue(d)));
                }
                stringAttributes.add(new StringAttribute(attribute.getName(), v));
            }
        }
        
        
        var.setDoubleAttributes(doubleAttributes);
        var.setFloatAttributes(floatAttributes);
        var.setShortAttributes(shortAttributes);
        var.setIntAttributes(intAttributes);
        var.setLongAttributes(longAttributes);
        var.setStringAttributes(stringAttributes);
        
        
        GridCoordSys gcs = (GridCoordSys) grid.getCoordinateSystem();
        Projection p = gcs.getProjection();
        if ( p instanceof LatLonProjection ) {
            var.setProjection(NetCDFVariable.Projection.LatLonProjection);
        }
        else if(p instanceof AlbersEqualArea ) {
            var.setProjection(NetCDFVariable.Projection.AlbersEqualArea);
        }
        else if(p instanceof FlatEarth ) {
            var.setProjection(NetCDFVariable.Projection.FlatEarth);
        }
        else if(p instanceof LambertAzimuthalEqualArea ) {
            var.setProjection(NetCDFVariable.Projection.LambertAzimuthalEqualArea);
        }
        else if(p instanceof LambertConformal ) {
            var.setProjection(NetCDFVariable.Projection.LambertConformal);
        }
        else if(p instanceof Mercator ) {
            var.setProjection(NetCDFVariable.Projection.Mercator);
        }
        else if(p instanceof Orthographic ) {
            var.setProjection(NetCDFVariable.Projection.Orthographic);
        }
        else if(p instanceof ProjectionAdapter ) {
            var.setProjection(NetCDFVariable.Projection.ProjectionAdapter);
        }
        else if(p instanceof RotatedLatLon ) {
            var.setProjection(NetCDFVariable.Projection.RotatedLatLon);
        }
        else if(p instanceof RotatedPole ) {
            var.setProjection(NetCDFVariable.Projection.RotatedPole);
        }
        else if(p instanceof Stereographic ) {
            var.setProjection(NetCDFVariable.Projection.Stereographic);
        }
        else if(p instanceof TransverseMercator ) {
            var.setProjection(NetCDFVariable.Projection.TransverseMercator);
        }
        else if(p instanceof UtmProjection ) {
            var.setProjection(NetCDFVariable.Projection.UtmProjection);
        }
        else if(p instanceof VerticalPerspectiveView ) {
            var.setProjection(NetCDFVariable.Projection.VerticalPerspectiveView);
        }
        
        LatLonRect rect = gcs.getLatLonBoundingBox();
        var.setLatMin(rect.getLatMin());
        var.setLatMax(rect.getLatMax());
        var.setLonMin(rect.getLonMin());
        var.setLonMax(rect.getLonMax());
        
        
        if ( gcs.hasTimeAxis() ) {
            CoordinateAxis1DTime timeAxis1D = null;
            TimeAxis timeAxis = new TimeAxis();
            CoordinateAxis tAxis = gcs.getTimeAxis();
            if ( tAxis instanceof CoordinateAxis1DTime ) {
                timeAxis1D = (CoordinateAxis1DTime) tAxis;
                double[] times = timeAxis1D.getCoordValues();
                if ( times.length > 0 ) {
                    for (int i = 1; i < times.length; i++) {
                        if ( !((times[i] - times[i-1]) > 0.d) ) {
                            System.err.println("Returning a null because the time axis exists, but is not monotonic.");
                            int im1 = i-1;                    
                            var.setError("Time axis is not monotonic at "+i+" with values "+times[i-1]+" at "+im1+" and "+times[i]+" at "+i);
                            return var;
                        }
                    }
                } else {
                    System.err.println("Returning a null because the time axis exists, but has no values in it.");
                    var.setError("Time axis exists, but has no values in it.");
                    return var;
                }
            }
            timeAxis.setBoundaryRef(tAxis.getBoundaryRef());
            double minvalue;
            double maxvalue;
            try{
                maxvalue = tAxis.getMaxValue();
                minvalue = tAxis.getMinValue();
                timeAxis.setMinValue(minvalue);
                timeAxis.setMaxValue(maxvalue);

            } catch ( Exception e) {
                // This is not a double valued axis.  We're skipping it for now.  :-)
                System.err.println("Returning a null variable because of "+e.getLocalizedMessage());
                var.setError("Procesing variable threw an error "+e.getLocalizedMessage());
                return var;
            }

            timeAxis.setIsContiguous(tAxis.isContiguous());
            timeAxis.setPositive(tAxis.getPositive());           
            timeAxis.setIsNumeric(tAxis.isNumeric());
            timeAxis.setElementSize(tAxis.getElementSize());
            timeAxis.setUnitsString(tAxis.getUnitsString());
            timeAxis.setName(tAxis.getName());

            timeAxis.setCalendar(getCalendarIfExists(tAxis.getAttributes()));

            // Older versions of Java netCDF only knew Gregorian, so we do everything ourselves when the calendar is not gregorian.
            // This can now change when we move to the new Jave netCDF
            DateRange range = gcs.getDateRange();
            setTime(timeAxis, tAxis, range);            
            var.setTimeAxis(timeAxis);
        }
        
        if ( gcs.hasVerticalAxis() ) {
            VerticalAxis zAxis = new VerticalAxis();
            CoordinateAxis1D coordAxis = gcs.getVerticalAxis();
            zAxis.setSize(coordAxis.getSize());
            zAxis.setPositive(coordAxis.getPositive());
            zAxis.setUnitsString(coordAxis.getUnitsString());
            zAxis.setIsContiguous(coordAxis.isContiguous());
            zAxis.setIsNumeric(coordAxis.isNumeric());
            zAxis.setElementSize(coordAxis.getElementSize());
            zAxis.setName(coordAxis.getName());
            zAxis.setStart(coordAxis.getStart());
            zAxis.setResolution(coordAxis.getIncrement());
            zAxis.setMinValue(coordAxis.getMinValue());
            zAxis.setMaxValue(coordAxis.getMaxValue());
            if ( coordAxis.isRegular() ) {
                zAxis.setIsRegular(true);
                zAxis.setResolution(coordAxis.getIncrement());
            } else {
                double v[] = coordAxis.getCoordValues();
                zAxis.setValues(v);
            }
            var.setVerticalAxis(zAxis);
        }
        
        CoordinateAxis yCoordAxis = gcs.getYHorizAxis();
        GeoAxis yAxis;
        try {
            yAxis = makeGeoAxis("y", yCoordAxis);
            var.setyAxis(yAxis);
        } catch ( Exception e ) {
            System.err.println("Returning a null variable because of "+e.getLocalizedMessage());
            var.setError("Processing variable threw an error: "+e.getLocalizedMessage());
            return var;
        }
       
        CoordinateAxis xCoordAxis = gcs.getXHorizAxis();
        GeoAxis xAxis;
        try {
            xAxis = makeGeoAxis("x", xCoordAxis);
            var.setxAxis(xAxis);
        } catch (Exception e) {
            System.err.println("Returning a null variable because of "+e.getLocalizedMessage());
            var.setError("Processing variable threw an error: "+e.getLocalizedMessage());
            return var;
        }
    
        return var;
        
    }
    private void setTime(TimeAxis timeAxis, CoordinateAxis tAxis, DateRange range) {
        if ((range!=null && range.getStart() != null && range.getEnd() != null) && (timeAxis.getCalendar()==null || timeAxis.getCalendar().toLowerCase().equals("gregorian")) && tAxis.getMinValue() >=0 ){
            timeAxis.setTimeCoverageStart(range.getStart().getText());
            timeAxis.setTimeCoverageEnd(range.getEnd().getText());
            timeAxis.setSize(tAxis.getSize());
        } else {
            // Weird calendar or suspicious negative first value, do the work ourselves
            CoordinateAxis1DTime axis = (CoordinateAxis1DTime) tAxis;
            if ( axis.getSize() > 2 ) {
                double t0 = axis.getCoordValue(0);
                double t1 = axis.getCoordValue(1);
                double tN = axis.getCoordValue((int)axis.getSize()-1);
                if ( t0 < -1000 && t1 >= 0.0 ) {
                    timeAxis.setMinValue(t1);
                    timeAxis.setMaxValue(tN);
                }
                AxisBean tAxisBean = ADDXMLProcessor.makeTimeAxisStartEnd((CoordinateAxis1DTime) tAxis);
                timeAxis.setSize(Long.valueOf(tAxisBean.getArange().getSize()));
                timeAxis.setTimeCoverageStart(tAxisBean.getArange().getStart());
                timeAxis.setTimeCoverageEnd(tAxisBean.getArange().getEnd());
            }
        }
    }
    public static GeoAxis makeGeoAxis (String type, CoordinateAxis axis) throws Exception {
        GeoAxis geoAxis = new GeoAxis();
        geoAxis.setType(type);
        geoAxis.setBoundaryRef(axis.getBoundaryRef());
        double minvalue;
        double maxvalue;

        maxvalue = axis.getMaxValue();
        minvalue = axis.getMinValue();
        geoAxis.setMinValue(minvalue);
        geoAxis.setMaxValue(maxvalue);

        geoAxis.setSize(axis.getSize());  
        geoAxis.setUnitsString(axis.getUnitsString());
        geoAxis.setIsContiguous(axis.isContiguous());
        geoAxis.setIsNumeric(axis.isNumeric());
        geoAxis.setElementSize(axis.getElementSize());
        geoAxis.setName(axis.getName());
        
        if ( axis instanceof CoordinateAxis2D ) {
            // Arbitrarily divide the space into 50 grid points.
            geoAxis.setSize(50);
            double range = Math.abs(maxvalue - minvalue);
            
        }
        
        return geoAxis;

    }
    private String getCalendarIfExists(List<Attribute> attributes){
        for(int i=0; i<attributes.size(); i++){
            if(attributes.get(i).getName() != null && attributes.get(i).getName().toLowerCase().equals("calendar")){
                return attributes.get(i).getStringValue();
            }
        }
        return null;
    }
}
