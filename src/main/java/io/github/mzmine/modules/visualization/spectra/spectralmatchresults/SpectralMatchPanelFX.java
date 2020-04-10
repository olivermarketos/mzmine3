package io.github.mzmine.modules.visualization.spectra.spectralmatchresults;

import io.github.mzmine.gui.chartbasics.chartthemes.EStandardChartTheme;
import io.github.mzmine.gui.chartbasics.gui.javafx.EChartViewer;
import io.github.mzmine.gui.chartbasics.gui.wrapper.ChartViewWrapper;
import io.github.mzmine.gui.chartbasics.listener.AxisRangeChangedListener;
import io.github.mzmine.main.MZmineCore;
import io.github.mzmine.modules.visualization.molstructure.Structure2DComponent;
import io.github.mzmine.modules.visualization.spectra.simplespectra.mirrorspectra.MirrorSpectrumUtil;
import io.github.mzmine.parameters.ParameterSet;
import io.github.mzmine.parameters.parametertypes.filenames.FileNameParameter;
import io.github.mzmine.util.color.ColorScaleUtil;
import io.github.mzmine.util.files.FileAndPathUtil;
import io.github.mzmine.util.javafx.FxColorUtil;
import io.github.mzmine.util.javafx.FxIconUtil;
import io.github.mzmine.util.spectraldb.entry.DBEntryField;
import io.github.mzmine.util.spectraldb.entry.SpectralDBEntry;
import io.github.mzmine.util.spectraldb.entry.SpectralDBPeakIdentity;
import io.github.mzmine.util.swing.SwingExportUtil;
import java.io.File;
import java.text.DecimalFormat;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.CombinedDomainXYPlot;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.Range;
import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.exception.InvalidSmilesException;
import org.openscience.cdk.inchi.InChIGeneratorFactory;
import org.openscience.cdk.inchi.InChIToStructure;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.smiles.SmilesParser;

public class SpectralMatchPanelFX extends GridPane implements Cloneable {

  private final Logger logger = Logger.getLogger(this.getClass().getName());

  private static final int ICON_WIDTH = 50;
  protected static final Image iconAll = FxIconUtil
      .loadImageFromResources("icons/exp_graph_all.png");
  protected static final Image iconPdf = FxIconUtil
      .loadImageFromResources("icons/exp_graph_pdf.png");
  protected static final Image iconEps = FxIconUtil
      .loadImageFromResources("icons/exp_graph_eps.png");
  protected static final Image iconEmf = FxIconUtil
      .loadImageFromResources("icons/exp_graph_emf.png");
  protected static final Image iconSvg = FxIconUtil
      .loadImageFromResources("icons/exp_graph_svg.png");

  private static final DecimalFormat COS_FORM = new DecimalFormat("0.000");

  private static Font font;

  public static final int META_WIDTH = 500;
  public static final int ENTRY_HEIGHT = 500;
  public static final int STRUCTURE_HEIGHT = 150;

  public static final double MIN_COS_COLOR_VALUE = 0.5;
  public static final double MAX_COS_COLOR_VALUE = 1.0;

  // min color is a darker red
  // max color is a darker green
  public static Color MAX_COS_COLOR = Color.web("0x388E3C");
  public static Color MIN_COS_COLOR = Color.web("0xE30B0B");

  private EChartViewer mirrorChart;

  private boolean setCoupleZoomY;

  private XYPlot queryPlot;
  private XYPlot libraryPlot;

  private VBox metaDataPanel;
  private VBox pnTitle;
  private GridPane pnExport;

  private Label lblScore;
  private Label lblHit;

  private EStandardChartTheme theme;

  private SpectralDBPeakIdentity hit;

  public SpectralMatchPanelFX(SpectralDBPeakIdentity hit) {
    super();

    setHit(hit);

    setMinSize(600, 500);

    theme = MZmineCore.getConfiguration().getDefaultChartTheme();

    metaDataPanel = new VBox();
    pnTitle = new VBox();
    pnTitle.setAlignment(Pos.CENTER);

    // create Top panel
    double simScore = hit.getSimilarity().getScore();
    Color gradientCol = FxColorUtil.awtColorToFX(ColorScaleUtil
        .getColor(FxColorUtil.fxColorToAWT(MIN_COS_COLOR), FxColorUtil.fxColorToAWT(MAX_COS_COLOR),
            MIN_COS_COLOR_VALUE, MAX_COS_COLOR_VALUE, simScore));
    pnTitle.setBackground(
        new Background(new BackgroundFill(gradientCol, CornerRadii.EMPTY, Insets.EMPTY)));

    lblHit = new Label(hit.getName());
    lblHit.getStyleClass().add("larger-label");

    lblScore = new Label(COS_FORM.format(simScore));
    lblScore.getStyleClass().add("score-label");
    lblScore
        .setTooltip(new Tooltip("Cosine similarity of raw data scan (top, blue) and database scan: "
            + COS_FORM.format(simScore)));

    pnTitle.getChildren().add(lblHit);
    pnTitle.getChildren().add(lblScore);

    // preview panel
    IAtomContainer molecule;
    BorderPane pnPreview2D = new BorderPane();
    pnPreview2D.setPrefSize(META_WIDTH, STRUCTURE_HEIGHT);
    pnPreview2D.setMinSize(META_WIDTH, STRUCTURE_HEIGHT);
    pnPreview2D.setMaxSize(META_WIDTH, STRUCTURE_HEIGHT);

    // TODO! - Export functionality for Java FX nodes
//    pnExport = new GridPane(); // wrapped in additional pane before
//    pnPreview2D.setRight(pnExport);
//    addExportButtons(MZmineCore.getConfiguration()
//        .getModuleParameters(SpectraIdentificationResultsModule.class));

    Node newComponent = null;

    String inchiString = hit.getEntry().getField(DBEntryField.INCHI).orElse("N/A").toString();
    String smilesString = hit.getEntry().getField(DBEntryField.SMILES).orElse("N/A").toString();

    // check for INCHI
    if (inchiString != "N/A") {
      molecule = parseInChi(hit);
    }
    // check for smiles
    else if (smilesString != "N/A") {
      molecule = parseSmiles(hit);
    } else {
      molecule = null;
    }

    // try to draw the component
    if (molecule != null) {
      try {
        newComponent = new Structure2DComponent(molecule, theme.getRegularFont());
      } catch (Exception e) {
        String errorMessage = "Could not load 2D structure\n" + "Exception: ";
        logger.log(Level.WARNING, errorMessage, e);
        newComponent = new Label(errorMessage);
        ((Label) newComponent).setWrapText(true);
      }
      pnPreview2D.setCenter(newComponent);

      metaDataPanel.getChildren().add(pnPreview2D);
    }

    ColumnConstraints ccMetadata1 = new ColumnConstraints(150, -1, Double.MAX_VALUE,
        Priority.NEVER, HPos.LEFT, false);
    ColumnConstraints ccMetadata2 = new ColumnConstraints(150, -1, Double.MAX_VALUE,
        Priority.NEVER, HPos.LEFT, false);
    ccMetadata1.setPercentWidth(50);
    ccMetadata2.setPercentWidth(50);

    GridPane g1 = new GridPane();
    BorderPane pnCompounds = extractMetaData("Compound information", hit.getEntry(),
        DBEntryField.COMPOUND_FIELDS);
    BorderPane panelInstrument =
        extractMetaData("Instrument information", hit.getEntry(), DBEntryField.INSTRUMENT_FIELDS);
    g1.add(pnCompounds, 0, 0);
    g1.add(panelInstrument, 1, 0);
    g1.getColumnConstraints().add(0, ccMetadata1);
    g1.getColumnConstraints().add(1, ccMetadata2);

    BorderPane pnDB =
        extractMetaData("Database links", hit.getEntry(), DBEntryField.DATABASE_FIELDS);
    BorderPane pnOther =
        extractMetaData("Other information", hit.getEntry(), DBEntryField.OTHER_FIELDS);
    g1.add(pnDB, 0, 1);
    g1.add(pnOther, 1, 1);

    metaDataPanel.getChildren().add(g1);

    metaDataPanel.setPrefSize(META_WIDTH, ENTRY_HEIGHT);

    mirrorChart = MirrorSpectrumUtil.createPlotFromSpectralDBPeakIdentity(hit);

    coupleZoomYListener();

    // put into main
    ColumnConstraints ccSpectrum = new ColumnConstraints(400, -1, Region.USE_COMPUTED_SIZE,
        Priority.ALWAYS, HPos.CENTER,
        true);
    ColumnConstraints ccMetadata = new ColumnConstraints(META_WIDTH + 20, META_WIDTH + 20,
        Region.USE_COMPUTED_SIZE, Priority.NEVER, HPos.LEFT, false);

//    ccSpectrum.setPercentWidth(70);

    add(pnTitle, 0, 0, 2, 1);
    add(mirrorChart, 0, 1);
    add(metaDataPanel, 1, 1);

    getColumnConstraints().add(0, ccSpectrum);
    getColumnConstraints().add(1, ccMetadata);

    setBorder(
        new Border(new BorderStroke(Color.BLACK, BorderStrokeStyle.SOLID, CornerRadii.EMPTY,
            BorderWidths.DEFAULT)));

  }

  private void coupleZoomYListener() {
    CombinedDomainXYPlot domainPlot = (CombinedDomainXYPlot) mirrorChart.getChart().getXYPlot();
    NumberAxis axis = (NumberAxis) domainPlot.getDomainAxis();
    axis.setLabel("m/z");
    queryPlot = (XYPlot) domainPlot.getSubplots().get(0);
    libraryPlot = (XYPlot) domainPlot.getSubplots().get(1);
    queryPlot.getRangeAxis().addChangeListener(new AxisRangeChangedListener(null) {
      @Override
      public void axisRangeChanged(ChartViewWrapper chart, ValueAxis axis, Range lastR,
          Range newR) {
        rangeHasChanged(newR);
      }
    });
    libraryPlot.getRangeAxis().addChangeListener(new AxisRangeChangedListener(null) {
      @Override
      public void axisRangeChanged(ChartViewWrapper chart, ValueAxis axis, Range lastR,
          Range newR) {
        rangeHasChanged(newR);
      }
    });
  }

  /**
   * Apply changes to all other charts
   *
   * @param range
   */
  private void rangeHasChanged(Range range) {
    if (setCoupleZoomY) {
      ValueAxis axis = libraryPlot.getRangeAxis();
      if (!axis.getRange().equals(range)) {
        axis.setRange(range);
      }
      ValueAxis axisQuery = queryPlot.getRangeAxis();
      if (!axisQuery.getRange().equals(range)) {
        axisQuery.setRange(range);
      }
    }
  }

  public EChartViewer getMirrorChart() {
    return mirrorChart;
  }

  public void setCoupleZoomY(boolean selected) {
    setCoupleZoomY = selected;
  }

  private IAtomContainer parseInChi(SpectralDBPeakIdentity hit) {
    String inchiString = hit.getEntry().getField(DBEntryField.INCHI).orElse("N/A").toString();
    InChIGeneratorFactory factory;
    IAtomContainer molecule;
    if (inchiString != "N/A") {
      try {
        factory = InChIGeneratorFactory.getInstance();
        // Get InChIToStructure
        InChIToStructure inchiToStructure =
            factory.getInChIToStructure(inchiString, DefaultChemObjectBuilder.getInstance());
        molecule = inchiToStructure.getAtomContainer();
        return molecule;
      } catch (CDKException e) {
        String errorMessage = "Could not load 2D structure\n" + "Exception: ";
        logger.log(Level.WARNING, errorMessage, e);
        return null;
      }
    } else {
      return null;
    }
  }

  private IAtomContainer parseSmiles(SpectralDBPeakIdentity hit) {
    SmilesParser smilesParser = new SmilesParser(DefaultChemObjectBuilder.getInstance());
    String smilesString = hit.getEntry().getField(DBEntryField.SMILES).orElse("N/A").toString();
    IAtomContainer molecule;
    if (smilesString != "N/A") {
      try {
        molecule = smilesParser.parseSmiles(smilesString);
        return molecule;
      } catch (InvalidSmilesException e1) {
        String errorMessage = "Could not load 2D structure\n" + "Exception: ";
        logger.log(Level.WARNING, errorMessage, e1);
        return null;
      }
    } else {
      return null;
    }
  }


  private BorderPane extractMetaData(String title, SpectralDBEntry entry, DBEntryField[] other) {
    VBox panelOther = new VBox();
    panelOther.setAlignment(Pos.TOP_LEFT);

    for (DBEntryField db : other) {
      Object o = entry.getField(db).orElse("N/A");
      if (!o.equals("N/A")) {
        Label text = new Label();
        text.setWrapText(true);
        text.setText(db.toString() + ": " + o.toString());
        panelOther.getChildren().add(text);
      }
    }

    Label otherInfo = new Label(title);
    otherInfo.getStyleClass().add("bold-title-label");
    BorderPane pn = new BorderPane();
    pn.setTop(otherInfo);
    pn.setCenter(panelOther);
//    JPanel pn1 = new JPanel(new BorderLayout());
//    pn1.add(pn, BorderLayout.NORTH);
//    pn1.setBackground(java.awt.Color.WHITE);
//    return pn1;
    return pn;
  }

  public void applySettings(ParameterSet param) {
    pnExport.getChildren().removeAll();
    addExportButtons(param);
  }

  /**
   * @param param {@link SpectraIdentificationResultsParameters}
   */
  private void addExportButtons(ParameterSet param) {
    Button btnExport = null;

    if (param.getParameter(SpectraIdentificationResultsParameters.all).getValue()) {
      ImageView img = new ImageView(iconAll);
      img.setPreserveRatio(true);
      img.setFitWidth(ICON_WIDTH);
      btnExport = new Button(null, img);
      btnExport.setMaxSize(ICON_WIDTH + 6, ICON_WIDTH + 6);
      btnExport.setOnAction(e -> exportToGraphics("all"));
      pnExport.add(btnExport, 0, 0);
    }

    if (param.getParameter(SpectraIdentificationResultsParameters.pdf).getValue()) {
      ImageView img = new ImageView(iconPdf);
      img.setPreserveRatio(true);
      img.setFitWidth(ICON_WIDTH);
      btnExport = new Button(null, img);
      btnExport.setMaxSize(ICON_WIDTH + 6, ICON_WIDTH + 6);
      btnExport.setOnAction(e -> exportToGraphics("pdf"));
      pnExport.add(btnExport, 0, 1);
    }

    if (param.getParameter(SpectraIdentificationResultsParameters.emf).getValue()) {
      ImageView img = new ImageView(iconEmf);
      img.setPreserveRatio(true);
      img.setFitWidth(ICON_WIDTH);
      btnExport = new Button(null, img);
      btnExport.setMaxSize(ICON_WIDTH + 6, ICON_WIDTH + 6);
      btnExport.setOnAction(e -> exportToGraphics("emf"));
      pnExport.add(btnExport, 0, 2);
    }

    if (param.getParameter(SpectraIdentificationResultsParameters.eps).getValue()) {
      ImageView img = new ImageView(iconEps);
      img.setPreserveRatio(true);
      img.setFitWidth(ICON_WIDTH);
      btnExport = new Button(null, img);
      btnExport.setMaxSize(ICON_WIDTH + 6, ICON_WIDTH + 6);
      btnExport.setOnAction(e -> exportToGraphics("eps"));
      pnExport.add(btnExport, 0, 3);
    }

    if (param.getParameter(SpectraIdentificationResultsParameters.svg).getValue()) {
      ImageView img = new ImageView(iconSvg);
      img.setPreserveRatio(true);
      img.setFitWidth(ICON_WIDTH);
      btnExport = new Button(null, img);
      btnExport.setMaxSize(ICON_WIDTH + 6, ICON_WIDTH + 6);
      btnExport.setOnAction(e -> exportToGraphics("svg"));
      pnExport.add(btnExport, 0, 4);
    }
  }

  public void exportToGraphics(String format) {
    // old path
    FileNameParameter param =
        MZmineCore.getConfiguration().getModuleParameters(SpectraIdentificationResultsModule.class)
            .getParameter(SpectraIdentificationResultsParameters.file);
    final FileChooser chooser;
    if (param.getValue() != null) {
      chooser = new FileChooser();
      chooser.setInitialDirectory(param.getValue().getParentFile());
    } else {
      chooser = new FileChooser();
    }

    // get file
    File f = chooser.showSaveDialog(null);
    if (f != null) {

      // attempt to put this node into a swing component to use the swing util to export
      // -> does not work, probably incompatible with JFXPanel :/
      Platform.runLater(() -> {
        try {
          pnExport.setVisible(false);
          Stage stage = (Stage) this.getScene().getWindow();

          if (stage instanceof SpectraIdentificationResultsWindowFX) {
            JFXPanel pn = new JFXPanel(); // I know this attempt is dirty

            SpectralMatchPanelFX pnHit = new SpectralMatchPanelFX(getHit());
            pn.setScene(new Scene(pnHit));
            pn.setSize((int) getWidth(), (int) getHeight());
            pnHit.setPrefSize(getWidth(), getHeight());
            pnHit.setMinSize(getWidth(), getHeight());

            pn.setVisible(true);
            pn.revalidate();

            // put this into a frame to test (works)
//            JFrame frame = new JFrame();
//            frame.setContentPane(pn);
//            frame.setVisible(true);
//            frame.revalidate();
//            frame.setSize((int) getWidth(), (int) getHeight());

            SwingExportUtil.writeToGraphics(pn, f.getParentFile(), f.getName(), format);
            // save path
            param.setValue(FileAndPathUtil.eraseFormat(f));

          }


        } catch (Exception ex) {
          logger.log(Level.WARNING, "Cannot export graphics of spectra match panel", ex);
        } finally {
          pnExport.setVisible(true);

        }
      });

//      Print using print to pdf - converts the spectrum to an image :/
//      probably since it is a swing node in a java fx environment

//      PrinterJob print = PrinterJob.createPrinterJob();
//      if(print != null){
//        print.showPrintDialog(null);
//        print.printPage(this);
//        print.endJob();
//      }

    }
  }

  public SpectralDBPeakIdentity getHit() {
    return hit;
  }

  private void setHit(SpectralDBPeakIdentity hit) {
    this.hit = hit;
  }

}




































