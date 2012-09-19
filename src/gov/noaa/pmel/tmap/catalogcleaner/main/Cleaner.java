package gov.noaa.pmel.tmap.catalogcleaner.main;

import gov.noaa.pmel.tmap.catalogcleaner.jdo.Catalog;
import gov.noaa.pmel.tmap.catalogcleaner.jdo.CatalogReference;
import gov.noaa.pmel.tmap.catalogcleaner.jdo.CatalogXML;
import gov.noaa.pmel.tmap.catalogcleaner.jdo.GeoAxis;
import gov.noaa.pmel.tmap.catalogcleaner.jdo.LeafDataset;
import gov.noaa.pmel.tmap.catalogcleaner.jdo.LeafNodeReference;
import gov.noaa.pmel.tmap.catalogcleaner.jdo.LeafNodeReference.DataCrawlStatus;
import gov.noaa.pmel.tmap.catalogcleaner.jdo.NetCDFVariable;
import gov.noaa.pmel.tmap.catalogcleaner.jdo.PersistenceHelper;
import gov.noaa.pmel.tmap.catalogcleaner.jdo.TimeAxis;
import gov.noaa.pmel.tmap.catalogcleaner.jdo.VerticalAxis;
import gov.noaa.pmel.tmap.cleaner.cli.CrawlerOptions;
import gov.noaa.pmel.tmap.cleaner.xml.JDOMUtils;
import gov.noaa.pmel.tmap.cleaner.xml.UrlPathFilter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import javax.jdo.JDOHelper;
import javax.jdo.PersistenceManager;
import javax.jdo.Transaction;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.ParseException;
import org.datanucleus.api.jdo.JDOPersistenceManagerFactory;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.Parent;
import org.jdom2.filter.ElementFilter;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.jdom2.util.IteratorIterable;
import org.joda.time.DateTime;

public class Cleaner {
    private static String viewer_0 = "http://ferret.pmel.noaa.gov/geoideLAS/getUI.do?data_url=";
    private static String viewer_0_description = ", Visualize with Live Access Server";
    
    private static String viewer_1 = "http://upwell.pfeg.noaa.gov/erddap/search/index.html?searchFor=";
    private static String viewer_1_description = ", Visualize with ERDDAP";
    
    private static String viewer_2 = "http://www.ncdc.noaa.gov/oa/wct/wct-jnlp.php?singlefile=";
    private static String viewer_2_description = ", Weather and Climate Toolkit";
    
    private static String root;
    private static String threddsContext;
    private static String threddsServer;
    private static String threddsServerName;
    private static String[] exclude;
    
    private static PersistenceHelper helper;
    private static Namespace ns = Namespace.getNamespace("http://www.unidata.ucar.edu/namespaces/thredds/InvCatalog/v1.0");
    private static Namespace netcdfns = Namespace.getNamespace("http://www.unidata.ucar.edu/namespaces/netcdf/ncml-2.2");
    private static Namespace xlink = Namespace.getNamespace("xlink", "http://www.w3.org/1999/xlink");
    /**
     * @param args
     */
    public static void main(String[] args) {
        CrawlerOptions crawlerOptions = new CrawlerOptions();
        Option s = crawlerOptions.getOption("s");
        s.setRequired(true);
        crawlerOptions.addOption(s);
        CommandLineParser parser = new GnuParser();
        CommandLine cl = null;
        int width = 80;
        try {
            cl = parser.parse(crawlerOptions, args);
            root = cl.getOptionValue("r");
            String url = cl.getOptionValue("u");
            threddsContext = cl.getOptionValue("c");
            exclude = cl.getOptionValues("x");
            if ( threddsContext == null ) {
                threddsContext = "thredds";
            }
            threddsServer = cl.getOptionValue("s");
            if ( !threddsServer.startsWith("http://")) {
                System.err.println("The server must be a full url, eg. http://ferret.pmel.noaa.gov:8080/");
                System.exit(-1);
            }
            if ( threddsServer.startsWith("http://")) threddsServerName = threddsServer.substring(7);
            if ( threddsServer.startsWith("https://")) threddsServerName = threddsServer.substring(8);
            String database = cl.getOptionValue("d");
            Properties properties = new Properties() ;
            URL propertiesURL =  ClassLoader.getSystemResource("datanucleus.properties");
            properties.load(new FileInputStream(new File(propertiesURL.getFile())));
            String connectionURL = (String) properties.get("datanucleus.ConnectionURL");
            if ( connectionURL.contains("database") ) {
                connectionURL = connectionURL.replace("database", database);
            } else {
                System.err.println("The conenctionURL string should use the name \"databast\" which will be substituted for each catalog" );
                System.exit(-1);
            }
            properties.setProperty("datanucleus.ConnectionURL", connectionURL);
            JDOPersistenceManagerFactory pmf = (JDOPersistenceManagerFactory) JDOHelper.getPersistenceManagerFactory(properties);
            PersistenceManager persistenceManager = pmf.getPersistenceManager();
            System.out.println("Starting clean work at "+DateTime.now().toString("yyyy-MM-dd HH:mm:ss"));
            helper = new PersistenceHelper(persistenceManager);
            Transaction tx = helper.getTransaction();
            tx.begin();
            Catalog catalog;
            CatalogXML catalogXML;
            if ( url != null ) {
                catalog = helper.getCatalog(root, url);
                catalogXML = helper.getCatalogXML(url);
            } else {
                catalog = helper.getCatalog(root, root);
                catalogXML = helper.getCatalogXML(root);
            }
            
            System.out.println("Doing a catalog clean for root "+root);
            if ( catalog == null || catalogXML == null ) {
                writeEmptyCatalog(root, url);   
            } else {
                clean(catalogXML, catalog);
            }
            List<CatalogReference> refs = catalog.getCatalogRefs();
            processChildren(root, refs);
            tx.commit();
            helper.close();
            System.out.println("All work complete.  Shutting down at "+DateTime.now().toString("yyyy-MM-dd HH:mm:ss"));
            System.exit(0);
        } catch ( ParseException e ) {
            System.err.println( e.getMessage() );
            HelpFormatter formatter = new HelpFormatter();
            formatter.setWidth(width);
            formatter.printHelp("TreeCrawler", crawlerOptions, true);
            System.exit(-1);

        } catch ( FileNotFoundException e ) {
            System.err.println(e.getLocalizedMessage());
            System.exit(-1);
        } catch ( IOException e ) {
            System.err.println(e.getLocalizedMessage());
            System.exit(-1);
        } catch ( JDOMException e ) {
            System.err.println(e.getLocalizedMessage());
            System.exit(-1);
        } catch ( URISyntaxException e ) {
            System.err.println(e.getLocalizedMessage());
            System.exit(-1);
        }
    }
    private static void processChildren(String url, List<CatalogReference> refs) throws IOException, JDOMException, URISyntaxException {
        for ( Iterator<CatalogReference> refsIt = refs.iterator(); refsIt.hasNext(); ) {
            CatalogReference catalogReference = refsIt.next();
            Catalog catalog = helper.getCatalog(url, catalogReference.getUrl());
            CatalogXML catalogXML = helper.getCatalogXML(catalogReference.getUrl());
            if ( catalog == null || catalogXML == null ) {
                writeEmptyCatalog(url, catalogReference.getUrl());   
            } else {
                clean(catalogXML, catalog);    
                if ( catalog.getCatalogRefs() != null && catalog.getCatalogRefs().size() > 0 ) {
                    processChildren(catalog.getUrl(), catalog.getCatalogRefs());
                }
            }
        }
    }
    private static void clean(CatalogXML catalogXML, Catalog catalog) throws IOException, JDOMException, URISyntaxException {
        System.out.println("Cleaning " + catalog.getUrl());
        // Prepare an XML document of the catalog.
        Document doc = new Document();
        String xml = catalogXML.getXml();
        if ( xml != null && xml.length() > 0 ) {
            JDOMUtils.XML2JDOM(xml, doc);
            if ( catalog.getLeafNodes() != null && catalog.getLeafNodes().size() > 0 ) {

                // Remove the services
                removeRemoteServices(doc);  
                addLocalServices(doc);

                List<LeafNodeReference> leaves = catalog.getLeafNodes();
                Map<String, List<LeafNodeReference>> aggregates = aggregate(catalog.getUrl(), leaves);

                for ( Iterator<String> aggIt = aggregates.keySet().iterator(); aggIt.hasNext(); ) {
                    String key = aggIt.next();
                    List<LeafNodeReference> aggs = aggregates.get(key);
                    // Remove the old data set references and add the new ncml.
                    addNCML(doc, catalog.getUrl(), aggs);
                    for ( Iterator<LeafNodeReference> aggsIt = aggs.iterator(); aggsIt.hasNext(); ) {
                        LeafNodeReference dataset = (LeafNodeReference) aggsIt.next();
                        System.out.println("\t\tAggreagate with: "+dataset.getUrl());
                    }           
                }
                // Remove child refs for best time series catalog...
                if ( catalog.hasBestTimeSeries() ) {
                    remove(doc, "catalogRef");
                }
            }
            updateCatalogReferences(doc.getRootElement(), catalog.getUrl(), catalog.getCatalogRefs());

            XMLOutputter xout = new XMLOutputter();
            Format format = Format.getPrettyFormat();
            format.setLineSeparator(System.getProperty("line.separator"));
            xout.setFormat(format);
            PrintStream fout;
            if ( catalog.getUrl().equals(catalog.getParent()) ) {
                fout = new PrintStream("CleanCatalog.xml"); 
            } else {
                URL catalogURL = new URL(catalog.getUrl());
                File ffile = new File("CleanCatalogs"+File.separator+catalogURL.getHost()+File.separator+catalogURL.getPath().substring(0, catalogURL.getPath().lastIndexOf("/")));
                ffile.mkdirs();
                File outFile = new File(ffile.getPath()+File.separator+catalogURL.getPath().substring(catalogURL.getPath().lastIndexOf("/")));
                fout = new PrintStream(outFile);
                System.out.println("Writing "+catalog.getUrl()+" \n\t\tto "+outFile.getAbsolutePath());
            }
            xout.output(doc, fout);
            fout.close();
        } else {
            System.err.println("Catalog XML does not exist for: "+catalogXML.getUrl());
        }

    }
    private static void writeEmptyCatalog(String parent, String url) throws IOException {
        Document doc = new Document();
        Element catalogE = new Element("catalog");
        catalogE.setAttribute("name", "Empty catalog for "+url);
        catalogE.addNamespaceDeclaration(xlink);
        Element documentation = new Element("documentation", ns);
        documentation.setAttribute("type", "Notes");
        documentation.setAttribute("title", "An automated process attempted to read this catalog and build a clean representation, but the catalog could not be read at the time the process ran.");
        catalogE.addContent(documentation);
        doc.addContent(catalogE);
        XMLOutputter xout = new XMLOutputter();
        Format format = Format.getPrettyFormat();
        format.setLineSeparator(System.getProperty("line.separator"));
        xout.setFormat(format);
        PrintStream fout;
        URL catalogURL = new URL(url);
        File ffile = new File("CleanCatalogs"+File.separator+catalogURL.getHost()+File.separator+catalogURL.getPath().substring(0, catalogURL.getPath().lastIndexOf("/")));
        ffile.mkdirs();
        fout = new PrintStream(ffile.getPath()+File.separator+catalogURL.getPath().substring(catalogURL.getPath().lastIndexOf("/")));
        xout.output(doc, fout);
        fout.close();
    }
    private static void updateCatalogReferences(Element element, String parent, List<CatalogReference> refs) throws MalformedURLException {
        List<Element> children = element.getChildren();
        List<Element> remove = new ArrayList<Element>();
        for ( Iterator refIt = children.iterator(); refIt.hasNext(); ) {
            Element child = (Element) refIt.next();
            if ( child.getName().equals("catalogRef") ) {                
                boolean convert = true;
                String href = child.getAttributeValue("href", xlink);
                for ( int i = 0; i < exclude.length; i++ ) {
                    if ( Pattern.matches(exclude[i], href)) {
                        remove.add(child);
                        convert = false;
                    }
                }
                
                if ( convert ) {
                    for ( Iterator refsIt = refs.iterator(); refsIt.hasNext(); ) {
                        CatalogReference reference = (CatalogReference) refsIt.next();
                        if ( reference.getOriginalUrl().equals(href) ) { 
                            if (href.startsWith("http")) {
                                URL catalogURL = new URL(reference.getUrl());
                                String dir = "CleanCatalogs"+File.separator+catalogURL.getHost()+catalogURL.getPath().substring(0, catalogURL.getPath().lastIndexOf("/"))+catalogURL.getPath().substring(catalogURL.getPath().lastIndexOf("/"));
                                child.setAttribute("href", dir, xlink);
                            } else {
                                URL parentURL = new URL(parent);
                                URL catalogURL = new URL(reference.getUrl());
                                String pfile = "CleanCatalogs"+File.separator+parentURL.getHost()+parentURL.getPath().substring(0, parentURL.getPath().lastIndexOf('/')+1);
                                String cfile = "CleanCatalogs"+File.separator+catalogURL.getHost()+catalogURL.getPath();
                                String ref = cfile.replace(pfile, "");
                                child.setAttribute("href", ref, xlink);
                            }
                        }
                    }
                }
            }
            updateCatalogReferences(child, parent, refs);
        }
        element.getChildren().removeAll(remove);
    }
    private static void remove(Document doc, String element) {
        Iterator removeIt = doc.getDescendants(new ElementFilter(element));
        Set<Parent> parents = new HashSet<Parent>();
        while ( removeIt.hasNext() ) {
            Element ref = (Element) removeIt.next();
            parents.add(ref.getParent());
        }
        for ( Iterator parentIt = parents.iterator(); parentIt.hasNext(); ) {
            Parent parent = (Parent) parentIt.next();
            parent.removeContent(new ElementFilter(element));
        }
    }
    private static void addNCML(Document doc, String parent, List<LeafNodeReference> aggs) throws MalformedURLException {

        boolean aggregating = aggs.size() > 1;

        LeafNodeReference leafNode = aggs.get(0); 
        LeafDataset data = helper.getLeafDataset(parent, leafNode.getUrl());

        Element matchingDataset = null;
        IteratorIterable datasetIt = doc.getRootElement().getDescendants(new UrlPathFilter(leafNode.getUrlPath()));
        int index = 0;
        Parent p = null;
        while (datasetIt.hasNext() ) {
            if ( index == 0 ) {
                Element dataset = (Element) datasetIt.next();
                matchingDataset = dataset;
                p = matchingDataset.getParent();
            }
            index++;
        }   
        Element ncml = new Element("netcdf", netcdfns);

        Element geospatialCoverage = new Element("geospatialCoverage", ns);

        List<Element> properties = new ArrayList<Element>();

        Element variables = new Element("variables", ns);
        variables.setAttribute("vocabulary", "CF-1.0");

        URL aggURL = new URL(aggs.get(0).getUrl());


        Element aggregation = new Element("aggregation", netcdfns);
        if ( !aggregating ) {
            ncml.setAttribute("location", aggs.get(0).getUrl());
        } else {
            aggregation.setAttribute("type", "joinExisting");
            ncml.addContent(aggregation);
            Element documentation = new Element("documentation", ns);
            documentation.setAttribute("type", "Notes");
            String catalogHTML = parent.substring(0, parent.lastIndexOf('.'))+".html";
            documentation.setAttribute("href", catalogHTML, xlink);
            documentation.setAttribute("title", "Aggregated from catalog "+catalogHTML+" starting with "+leafNode.getUrl().substring(0, leafNode.getUrl().lastIndexOf('/')), xlink);
            properties.add(documentation);
        }

        if ( data != null && data.getVariables().size() > 0) {
            // We are going to aggregate.  Get the 0th variable and use it to fill out the GeoSpaticalCoverage
            // By definition, any other variable in this collection should have the same characteristics.
            NetCDFVariable representativeVariable = data.getVariables().get(0);



            GeoAxis yaxis = representativeVariable.getyAxis();
            double latmax = representativeVariable.getLatMax();
            double latmin = representativeVariable.getLatMin();
            double latsize = latmax - latmin;
            String latunits = yaxis.getUnitsString();
            if ( latunits == null ) {
                latunits = "degN";
            }
            Element northsouth = new Element("northsouth", ns);
            Element ystart = new Element("start", ns);
            Element ysize = new Element("size", ns);
            Element yunits = new Element("units", ns);
            ystart.setText(String.valueOf(latmin));
            ysize.setText(String.valueOf(latsize));
            yunits.setText(latunits);
            northsouth.addContent(ystart);
            northsouth.addContent(ysize);
            northsouth.addContent(yunits);
            geospatialCoverage.addContent(northsouth);

            GeoAxis xaxis = representativeVariable.getxAxis();
            double lonmax = representativeVariable.getLonMax();
            double lonmin = representativeVariable.getLonMin();
            double lonsize = lonmax - lonmin;
            String lonunits = xaxis.getUnitsString();
            if ( lonunits == null ) {
                lonunits = "degE";
            }
            Element eastwest = new Element("eastwest", ns);
            Element xstart = new Element("start", ns);
            Element xsize = new Element("size", ns);
            Element xunits = new Element("units", ns);
            xstart.setText(String.valueOf(lonmin));
            xsize.setText(String.valueOf(lonsize));
            xunits.setText(lonunits);
            eastwest.addContent(xstart);
            eastwest.addContent(xsize);
            eastwest.addContent(xunits);
            geospatialCoverage.addContent(eastwest);

            Element ewPropertyNumberOfPoints = new Element("property", ns);
            ewPropertyNumberOfPoints.setAttribute("name", "eastwestPropertyNumberOfPoints");
            ewPropertyNumberOfPoints.setAttribute("value", String.valueOf(xaxis.getSize()));

            properties.add(ewPropertyNumberOfPoints);

            Element ewPropertyResolution = new Element("property", ns);
            ewPropertyResolution.setAttribute("name", "eastwestResolution");
            ewPropertyResolution.setAttribute("value", String.valueOf(lonsize/Double.valueOf(xaxis.getSize()-1)));

            properties.add(ewPropertyResolution);

            Element ewPropertyStart = new Element("property", ns);
            ewPropertyStart.setAttribute("name", "eastwestStart");
            ewPropertyStart.setAttribute("value", String.valueOf(lonmin));

            properties.add(ewPropertyStart);
            Element nsPropertyNumberOfPoints = new Element("property", ns);
            nsPropertyNumberOfPoints.setAttribute("name", "northsouthPropertyNumberOfPoints");
            nsPropertyNumberOfPoints.setAttribute("value", String.valueOf(yaxis.getSize()));

            properties.add(nsPropertyNumberOfPoints);

            Element nsPropertyResolution = new Element("property", ns);
            nsPropertyResolution.setAttribute("name", "northsouthResolution");
            nsPropertyResolution.setAttribute("value", String.valueOf(latsize/Double.valueOf(yaxis.getSize()-1)));

            properties.add(nsPropertyResolution);

            Element nsPropertyStart = new Element("property", ns);
            nsPropertyStart.setAttribute("name", "northsouthStart");
            nsPropertyStart.setAttribute("value", String.valueOf(latmin));

            properties.add(nsPropertyStart);

            VerticalAxis vert = representativeVariable.getVerticalAxis();
            if ( vert != null ) {
                String positive = vert.getPositive();
                if ( positive != null ) {
                    geospatialCoverage.setAttribute("zpositive", positive);
                }
                String min = String.valueOf(vert.getMinValue());
                double size = vert.getMaxValue() - vert.getMinValue();
                String units = vert.getUnitsString();
                if ( units == null ) {
                    units = "";
                }
                Element updown = new Element("updown", ns);
                Element zStart = new Element("start", ns);
                Element zSize = new Element("size",ns);
                Element zUnits = new Element("units", ns);
                zStart.setText(min);
                updown.addContent(zStart);
                zSize.setText(String.valueOf(size));
                updown.addContent(zSize);
                zUnits.setText(units);
                updown.addContent(zUnits);
                geospatialCoverage.addContent(updown);
                Element property = new Element("property", ns);
                property.setAttribute("name", "updownValues");
                String vs = vert.getValues();
                if ( vs == null ) vs = "NULL Values";
                property.setAttribute("value", vs);
                properties.add(property);
                String hasZ = "";
                for ( Iterator varIt = data.getVariables().iterator(); varIt.hasNext(); ) {
                    NetCDFVariable var = (NetCDFVariable) varIt.next();
                    hasZ = hasZ + var.getName() + " ";
                }
                Element hasZProperty = new Element("property", ns);
                hasZProperty.setAttribute("name", "hasZ");
                hasZProperty.setAttribute("value", hasZ.trim());
                properties.add(hasZProperty);
            }

            TimeAxis taxis = representativeVariable.getTimeAxis();
            if (taxis != null) {
                // Well, duh.  It's an aggregation.

                Element timeUnitsProperty = new Element("property", ns);
                timeUnitsProperty.setAttribute("name", "timeAxisUnits");
                timeUnitsProperty.setAttribute("value", taxis.getUnitsString());
                properties.add(timeUnitsProperty);

                aggregation.setAttribute("dimName", taxis.getName());
                String hasT = "";
                long tsize = 0;
                for ( Iterator varIt = data.getVariables().iterator(); varIt.hasNext(); ) {
                    NetCDFVariable var = (NetCDFVariable) varIt.next();
                    hasT = hasT + var.getName() + " ";
                }

                Element hasTProperty = new Element("property", ns);
                hasTProperty.setAttribute("name", "hasT");
                hasTProperty.setAttribute("value", hasT.trim());
                properties.add(hasTProperty);



            }
        } else {
            System.err.println("Data node information is null for "+parent+" ... "+leafNode.getUrl());
            return;
        }
        String timeStart = "";
        String timeEnd = "";
        long timeSize = 0;
        for ( int a = 0; a < aggs.size(); a++ ) {
            LeafNodeReference leafNodeReference = aggs.get(a);
            Element netcdf = new Element("netcdf", netcdfns);
            netcdf.setAttribute("location", leafNodeReference.getUrl());
            data = helper.getLeafDataset(parent, leafNodeReference.getUrl());
            if ( data.getVariables() != null && data.getVariables().size() > 0 ) {
                TimeAxis ta = data.getVariables().get(0).getTimeAxis();
                if ( ta != null ) {
                    if ( a == 0 ) {
                        timeStart = ta.getTimeCoverageStart();
                    }
                    if ( a == aggs.size() - 1 ) {
                        timeEnd = ta.getTimeCoverageEnd();
                    }
                    long tsize = ta.getSize();
                    timeSize = timeSize + tsize;

                    netcdf.setAttribute("ncoords", String.valueOf(tsize));
                }
                aggregation.addContent(netcdf);
            }
        }

        Element timeCoverageStart = new Element("property", ns);
        timeCoverageStart.setAttribute("name", "timeCoverageStart");
        timeCoverageStart.setAttribute("value", timeStart);
        properties.add(timeCoverageStart);

        Element timeSizeProperty = new Element("property", ns);
        timeSizeProperty.setAttribute("name", "timeCoverageNumberOfPoints");
        timeSizeProperty.setAttribute("value", String.valueOf(timeSize));
        properties.add(timeSizeProperty);

        Element time = new Element("timeCoverage", ns);
        Element start = new Element("start", ns);
        start.setText(timeStart);
        Element end = new Element("end", ns);
        end.setText(timeEnd);
        time.addContent(start);
        time.addContent(end);

        properties.add(time);

        String name = "";
        for ( Iterator varIt = data.getVariables().iterator(); varIt.hasNext(); ) {
            NetCDFVariable var = (NetCDFVariable) varIt.next();
            name = name + var.getName();
            if (varIt.hasNext()) name = name+"_";

            //<variable name="vwnd" units="m/s" vocabulary_name="mean Daily V wind" />
            Element variable = new Element("variable", ns);
            variable.setAttribute("name", var.getName());
            variable.setAttribute("units",var.getUnitsString());
            variable.setAttribute("vocabulary_name", var.getLongName());

            variables.addContent(variable);

        }

        String dataURL;
        if ( aggregating ) {
            dataURL = threddsServer+"/dodsC/"+name+"_aggregation";
        } else {
            dataURL = leafNode.getUrl();
        }
        Element viewer0Property = new Element("property", ns);
        viewer0Property.setAttribute("name", "viewer_0");
        viewer0Property.setAttribute("value", viewer_0+dataURL+viewer_0_description);

        properties.add(viewer0Property);

        Element viewer1Property = new Element("property", ns);
        viewer1Property.setAttribute("name", "viewer_1");
        viewer1Property.setAttribute("value", viewer_1+dataURL+viewer_1_description);

        properties.add(viewer1Property);

        Element viewer2Property = new Element("property", ns);
        viewer2Property.setAttribute("name", "viewer_2");
        viewer2Property.setAttribute("value", viewer_2+dataURL+viewer_2_description);

        properties.add(viewer2Property);


        if ( matchingDataset != null && aggregating) {
            String path = aggURL.getPath();
            path = path.substring(path.lastIndexOf("dodsC/")+6, path.lastIndexOf('/')+1);
            matchingDataset.setAttribute("urlPath", path+name+"_aggregation");
            matchingDataset.setAttribute("name", name);
        }
        if ( p != null ) {
            for ( int i = 1; i < aggs.size(); i++ ) {
                LeafNodeReference leafNodeReference = aggs.get(i);
                p.removeContent(new UrlPathFilter(leafNodeReference.getUrlPath()));
            }
        }


        if ( matchingDataset != null ) {
            Element service = new Element("serviceName", ns);
            service.addContent( threddsServerName+"_compound");
            matchingDataset.addContent(0, service);
            matchingDataset.addContent(0, geospatialCoverage);
            matchingDataset.addContent(0, properties);
            Element varE = matchingDataset.getChild("variables", ns);
            Element varEP = matchingDataset.getParentElement().getChild("variables", ns);
            Element metadataP = matchingDataset.getParentElement().getChild("metadata", ns);
            if ( varEP == null ) {
                if (metadataP != null) {
                    varEP = metadataP.getChild("variables", ns);
                }
            }
            // This is a bit of a kludge because they could be in the grandparent, but probably not...
            if ( varE == null && varEP == null ) {
                matchingDataset.addContent(0, variables);
            }
            matchingDataset.addContent(ncml);
        }
    }
    private static void removeRemoteServices(Document doc ) {
        remove(doc, "service");
        remove(doc, "serviceName");
        Iterator<Element> metaIt = doc.getRootElement().getDescendants(new ElementFilter("metadata"));
        List<Parent> parents = new ArrayList<Parent>();
        while ( metaIt.hasNext() ) {
            Element meta = metaIt.next();
            List<Element> children = meta.getChildren();
            if ( children == null || children.size() == 0 ) {
                parents.add(meta.getParent());
            }
        }
        for ( Iterator parentIt = parents.iterator(); parentIt.hasNext(); ) {
            Parent parent = (Parent) parentIt.next();
            parent.removeContent(new ElementFilter("metadata"));
        }
    }
    private static void addLocalServices(Document doc) {
        Element service = new Element("service", ns);
        service.setAttribute("name", threddsServerName+"_compound");
        service.setAttribute("serviceType", "compound");
        service.setAttribute("base", "");
        addService(service, "wms", "WMS");
        addService(service, "wcs", "WCS");
        addService(service, "ncml", "NCML");
        addService(service, "iso", "ISO");
        addService(service, "uddc", "UDDC");
        addService(service, "dodsC", "OPeNDAP");
        // Put this at the top of the document in index 0.
        doc.getRootElement().addContent(0, service);
    }
    private static void addService ( Element compoundService, String base, String type) {
        Element service = new Element("service", ns);
        service.setAttribute("name", threddsServerName+"_"+base);
        String fullbase = threddsServer;
        if ( !fullbase.endsWith("/")) fullbase = fullbase + "/";
        fullbase = fullbase + threddsContext;
        if ( !fullbase.endsWith("/") ) fullbase = fullbase + "/";
        fullbase = fullbase + base;
        if ( !fullbase.endsWith("/") ) fullbase = fullbase + "/";
        service.setAttribute("base", fullbase);
        service.setAttribute("serviceType", type);
        compoundService.addContent(service);
    }
    private static Map<String, List<LeafNodeReference>> aggregate(String parent, List<LeafNodeReference> leaves) throws UnsupportedEncodingException {
        Map<String, List<LeafNodeReference>> aggregates = new HashMap<String, List<LeafNodeReference>>();
        for ( Iterator<LeafNodeReference> leafIt = leaves.iterator(); leafIt.hasNext(); ) {
            LeafNodeReference leafNodeReference = leafIt.next();
            if ( leafNodeReference.getDataCrawlStatus() == DataCrawlStatus.FINISHED ) {
                LeafDataset dataset = helper.getLeafDataset(parent, leafNodeReference.getUrl());
                String signature = "";
                if ( dataset != null ) { // Data set may be listed, but has not yet been crawled, but status should have caught this above
                    List<NetCDFVariable> variables = dataset.getVariables();
                    if ( variables != null ) {
                        for ( Iterator<NetCDFVariable> varIt = variables.iterator(); varIt.hasNext(); ) {
                            NetCDFVariable dsvar = varIt.next();
                            signature = dsvar.getName() + dsvar.getRank() + dsvar.getxAxis() + dsvar.getyAxis();
                            if ( dsvar.getTimeAxis() != null ) {
                                signature = signature + dsvar.getTimeAxis();
                            }
                            if ( dsvar.getVerticalAxis() != null ) {
                                signature = signature + dsvar.getVerticalAxis();
                            }
                        }
                    }
                }
                String key = JDOMUtils.MD5Encode(signature);
                List<LeafNodeReference> datasets  = aggregates.get(key);
                if ( datasets == null ) {
                    datasets = new ArrayList<LeafNodeReference>();
                    aggregates.put(key, datasets);
                }
                datasets.add(leafNodeReference);
            }
        }
        return aggregates;
    }
}