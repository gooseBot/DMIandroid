package com.ericjackson.dmiandroid;

import android.database.Cursor;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.location.Location;
import com.readystatesoftware.sqliteasset.SQLiteAssetHelper;

public class dbHelper extends SQLiteAssetHelper {
    // If you change the database schema, you must increment the database version.
    public static final int DATABASE_VERSION = 1;
    public static final String DATABASE_NAME = "sr24kPointsAll2017v2.sqlite";

    public dbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    public milePostLocation getNearbySRMPlocations(Location myLocation) {

        SQLiteDatabase db = getReadableDatabase();
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        double bboxLat1 = myLocation.getLatitude() - 0.0004;
        double bboxLat2 = myLocation.getLatitude() + 0.0004;
        double bboxLong1 = myLocation.getLongitude() - 0.0004;
        double bboxLong2 = myLocation.getLongitude() + 0.0004;

        String[] sqlSelect = {"_id", "routeid", "arm", "srmp",
                "direction", "longitude", "latitude", "stateRoute",
                "relRouteTy", "relRouteQu", "aheadBackI"};
        String sqlTables = "sr24kpointsall2017";
        String whereClause = "latitude >= CAST(? AS REAL) AND latitude <= CAST(? AS REAL) AND " +
                "longitude >= CAST(? AS REAL) AND longitude <= CAST(? AS REAL)";

        String[] whereArgs = new String[]{
                Double.toString(bboxLat1),
                Double.toString(bboxLat2),
                Double.toString(bboxLong1),
                Double.toString(bboxLong2)
        };

        qb.setTables(sqlTables);
        Cursor c = qb.query(db, sqlSelect, whereClause, whereArgs,
                null, null, null);
        if (c.getCount()>0) {
            c.moveToFirst();
            Location loc2 = new Location("");
            double distanceInMeters = 1000000;
            int minDistanceRowPosition = 0;
            while (!c.isAfterLast()) {
                double longitude = c.getDouble(c.getColumnIndex("longitude"));
                double latitude = c.getDouble(c.getColumnIndex("latitude"));
                loc2.setLatitude(latitude);
                loc2.setLongitude(longitude);
                double tempDistance = myLocation.distanceTo(loc2);
                if (tempDistance < distanceInMeters) {
                    distanceInMeters = tempDistance;
                    minDistanceRowPosition = c.getPosition();
                }
                c.moveToNext();
            }

            //find the top nearest to my location
            c.moveToPosition(minDistanceRowPosition);
            return new milePostLocation(c.getInt(c.getColumnIndex("arm")),
                    c.getInt(c.getColumnIndex("srmp")),
                    c.getString(c.getColumnIndex("direction")),
                    c.getString(c.getColumnIndex("stateroute")),
                    c.getString(c.getColumnIndex("relroutety")),
                    c.getString(c.getColumnIndex("relroutequ")),
                    c.getString(c.getColumnIndex("aheadbacki")),
                    c.getDouble(c.getColumnIndex("latitude")),
                    c.getDouble(c.getColumnIndex("longitude")));
        }
        else
        {
            return new milePostLocation(0,0,"","",
                    "","","",0,0);
        }
    }

    public milePostLocation getSRMPinfo(milePostLocation armLocation){
        SQLiteDatabase db = getReadableDatabase();
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        String[] sqlSelect = {"_id", "routeid", "arm", "srmp",
                "direction", "longitude", "latitude", "stateRoute",
                "relRouteTy", "relRouteQu", "aheadBackI"};
        String sqlTables = "sr24kpointsall2017";
        // all ? must be as strings, an android limitation, CAST operation used to convert back to proper type for SQL
        String whereClause = "routeid = ? AND direction = ? AND arm = CAST(? AS INTEGER)";
        String[] whereArgs = new String[]{
                armLocation.getstateRoute() + armLocation.getrelRouteType() + armLocation.getrelRouteQualifier(),
                armLocation.getdirection(),
                Integer.toString(armLocation.getArm())};

        qb.setTables(sqlTables);
        Cursor c = qb.query(db, sqlSelect, whereClause, whereArgs,
                null, null, null);

        if (c.getCount()>0) {
            c.moveToFirst();
            //should only be one hit per unique location, as far as I know!
            return new milePostLocation(c.getInt(c.getColumnIndex("arm")),
                    c.getInt(c.getColumnIndex("srmp")),
                    c.getString(c.getColumnIndex("direction")),
                    c.getString(c.getColumnIndex("stateroute")),
                    c.getString(c.getColumnIndex("relroutety")),
                    c.getString(c.getColumnIndex("relroutequ")),
                    c.getString(c.getColumnIndex("aheadbacki")),
                    c.getDouble(c.getColumnIndex("latitude")),
                    c.getDouble(c.getColumnIndex("longitude")));
        } else
        {
            return new milePostLocation(0,0,"","",
                    "","","",0,0);
        }
    }

//    private static int[] getThreeLowest(int[] array) {
//        int[] lowestValues = new int[3];
//        Arrays.fill(lowestValues, Integer.MAX_VALUE);
//
//        for(int n : array) {
//            if(n < lowestValues[2]) {
//                lowestValues[2] = n;
//                Arrays.sort(lowestValues);
//            }
//        }
//        return lowestValues;
//    }

}
