package com.ericjackson.dmiandroid;

public class milePostLocation
{
    private int arm;
    private int srmp;
    private String direction;
    private double longitude;
    private double latitude;
    private String stateRoute;
    private String relRouteType;
    private String relRouteQualifier;
    private String aheadBackIndicator;
    public milePostLocation(int arm,
                            int srmp,
                            String direction,
                            String stateRoute,
                            String relRouteType,
                            String relRouteQualifier,
                            String aheadBackIndicator,
                            double latitude,
                            double longitude)
    {
        this.arm = arm;
        this.srmp = srmp;
        this.direction = direction;
        this.stateRoute = stateRoute;
        this.relRouteType = (relRouteType==null)?"":relRouteType;
        this.relRouteQualifier = (relRouteQualifier==null)?"":relRouteQualifier;
        this.aheadBackIndicator = aheadBackIndicator;
        this.latitude = latitude;
        this.longitude = longitude;
    }
    public int getArm() { return arm; }
    public int getSrmp() { return srmp; }
    public String getdirection() { return direction; }
    public String getstateRoute() { return stateRoute; }
    public String getrelRouteType() { return relRouteType; }
    public String getrelRouteQualifier() { return relRouteQualifier; }
    public String getaheadBackIndicator() { return aheadBackIndicator; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return  longitude; }
    public String toString() { return stateRoute +
            relRouteType +
            relRouteQualifier +
            " " + String.valueOf(srmp) + aheadBackIndicator +
            " " + String.valueOf(arm); }
}