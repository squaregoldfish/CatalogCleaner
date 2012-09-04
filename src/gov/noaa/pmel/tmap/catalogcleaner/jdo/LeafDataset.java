package gov.noaa.pmel.tmap.catalogcleaner.jdo;

import java.util.Enumeration;
import java.util.List;

import javax.jdo.annotations.Column;
import javax.jdo.annotations.PersistenceCapable;
import javax.jdo.annotations.Persistent;

@PersistenceCapable
public class LeafDataset {
    
    @Persistent
    @Column(length=500)
    private String url;
    
    @Persistent
    @Column(length=500)
    private String parent;
    
    @Persistent
    CatalogComment comment;
    
    @Persistent
    List<NetCDFVariable> variables;
    

    public LeafDataset(String parent, String url) {
        this.parent = parent;
        this.url = url;
    }

    public CatalogComment getComment() {
        return comment;
    }

    public void setComment(CatalogComment comment) {
        this.comment = comment;
    }

    public String getParent() {
        return parent;
    }

    public void setParent(String parent) {
        this.parent = parent;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<NetCDFVariable> getVariables() {
        return variables;
    }

    public void setVariables(List<NetCDFVariable> variables) {
        this.variables = variables;
    }
    

}