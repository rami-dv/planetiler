package com.onthegomap.flatmap.reader;

import com.onthegomap.flatmap.Profile;
import com.onthegomap.flatmap.collection.FeatureGroup;
import com.onthegomap.flatmap.config.CommonParams;
import com.onthegomap.flatmap.stats.Stats;
import com.onthegomap.flatmap.util.FileUtils;
import com.onthegomap.flatmap.worker.WorkerPipeline;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import org.geotools.data.FeatureSource;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.feature.FeatureCollection;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;

public class ShapefileReader extends Reader implements Closeable {

  private final FeatureCollection<SimpleFeatureType, SimpleFeature> inputSource;
  private final String[] attributeNames;
  private final ShapefileDataStore dataStore;
  private MathTransform transformToLatLon;

  public static void process(String sourceProjection, String sourceName, Path input, FeatureGroup writer,
    CommonParams config,
    Profile profile, Stats stats) {
    try (var reader = new ShapefileReader(sourceProjection, sourceName, input, profile, stats)) {
      reader.process(writer, config);
    }
  }

  public static void process(String sourceName, Path input, FeatureGroup writer, CommonParams config, Profile profile,
    Stats stats) {
    process(null, sourceName, input, writer, config, profile, stats);
  }

  public ShapefileReader(String sourceProjection, String sourceName, Path input, Profile profile, Stats stats) {
    super(profile, stats, sourceName);
    dataStore = decode(input);
    try {
      String typeName = dataStore.getTypeNames()[0];
      FeatureSource<SimpleFeatureType, SimpleFeature> source =
        dataStore.getFeatureSource(typeName);

      inputSource = source.getFeatures(Filter.INCLUDE);
      CoordinateReferenceSystem src =
        sourceProjection == null ? source.getSchema().getCoordinateReferenceSystem() : CRS.decode(sourceProjection);
      CoordinateReferenceSystem dest = CRS.decode("EPSG:4326", true);
      transformToLatLon = CRS.findMathTransform(src, dest);
      if (transformToLatLon.isIdentity()) {
        transformToLatLon = null;
      }
      attributeNames = new String[inputSource.getSchema().getAttributeCount()];
      for (int i = 0; i < attributeNames.length; i++) {
        attributeNames[i] = inputSource.getSchema().getDescriptor(i).getLocalName();
      }
    } catch (IOException | FactoryException e) {
      throw new RuntimeException(e);
    }
  }

  private ShapefileDataStore decode(Path path) {
    try {

      URI uri;

      if (FileUtils.hasExtension(path, "zip")) {
        try (var zipFs = FileSystems.newFileSystem(path)) {
          Path shapeFileInZip = FileUtils.walkFileSystem(zipFs)
            .filter(z -> FileUtils.hasExtension(z, "shp"))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No .shp file found inside " + path));
          uri = shapeFileInZip.toUri();
        }
      } else if (FileUtils.hasExtension(path, "shp")) {
        uri = path.toUri();
      } else {
        throw new IllegalArgumentException("Invalid shapefile input: " + path + " must be zip or shp");
      }
      return new ShapefileDataStore(uri.toURL());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public ShapefileReader(String name, Path input, Profile profile, Stats stats) {
    this(null, name, input, profile, stats);
  }

  @Override
  public long getCount() {
    return inputSource.size();
  }

  @Override
  public WorkerPipeline.SourceStep<ReaderFeature> read() {
    return next -> {
      try (var iter = inputSource.features()) {
        long id = 0;
        while (iter.hasNext()) {
          id++;
          SimpleFeature feature = iter.next();
          Geometry source = (Geometry) feature.getDefaultGeometry();
          Geometry latLonGeometry = source;
          if (transformToLatLon != null) {
            latLonGeometry = JTS.transform(source, transformToLatLon);
          }
          if (latLonGeometry != null) {
            ReaderFeature geom = new ReaderFeature(latLonGeometry, attributeNames.length, sourceName, null, id);
            for (int i = 1; i < attributeNames.length; i++) {
              geom.setTag(attributeNames[i], feature.getAttribute(i));
            }
            next.accept(geom);
          }
        }
      }
    };
  }

  @Override
  public void close() {
    dataStore.dispose();
  }
}