package com.pinghang.util;

/* ====================================================================
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 ==================================================================== */

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.poi.hssf.usermodel.HSSFDateUtil;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.BuiltinFormats;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.pinghang.exception.MyExcelSheetHeadModeManualException;

/**
 * 使用CVS模式解决XLSX文件，可以有效解决用户模式内存溢出的问题
 * 该模式是POI官方推荐的读取大数据的模式，在用户模式下，数据量较大、Sheet较多、或者是有很多无用的空行的情况
 * ，容易出现内存溢出,用户模式读取Excel的典型代码如下： FileInputStream file=new
 * FileInputStream("c:\\test.xlsx"); Workbook wb=new XSSFWorkbook(file);
 * 
 * 
 * @author 山人
 */
public class XlsXReaderEngine {

	/**
	 * The type of the data value is indicated by an attribute on the cell. The
	 * value is usually in a "v" element within the cell.
	 */
	enum xssfDataType {
		BOOL, ERROR, FORMULA, INLINESTR, SSTINDEX, NUMBER,
	}

	/**
	 * 使用xssf_sax_API处理Excel,请参考：
	 * http://poi.apache.org/spreadsheet/how-to.html#xssf_sax_api
	 * <p/>
	 * Also see Standard ECMA-376, 1st edition, part 4, pages 1928ff, at
	 * http://www.ecma-international.org/publications/standards/Ecma-376.htm
	 * <p/>
	 * A web-friendly version is http://openiso.org/Ecma/376/Part4
	 */
	class MyXSSFSheetHandler extends DefaultHandler {

		/**
		 * Table with styles
		 */
		private StylesTable stylesTable;

		/**
		 * Table with unique strings
		 */
		private ReadOnlySharedStringsTable sharedStringsTable;

		/**
		 * Destination for data
		 */
		private final PrintStream output;

		/**
		 * Number of columns to read starting with leftmost
		 */
		private final int minColumnCount;

		// Set when V start element is seen
		private boolean vIsOpen;

		// Set when cell start element is seen;
		// used when cell close element is seen.
		private xssfDataType nextDataType;

		// Used to format numeric cell values.
		private short formatIndex;
		private String formatString;
		private final DataFormatter formatter;

		private int thisColumn = -1;
		// The last column printed to the output stream
		private int lastColumnNumber = -1;

		// Gathers characters as they are seen.
		private StringBuffer value;
		private String[] record;
		private List<String[]> rows = new ArrayList<String[]>();
		private boolean isCellNull = false;

		/**
		 * Accepts objects needed while parsing.
		 * 
		 * @param styles
		 *            Table of styles
		 * @param strings
		 *            Table of shared strings
		 * @param cols
		 *            Minimum number of columns to show
		 * @param target
		 *            Sink for output
		 */
		public MyXSSFSheetHandler(StylesTable styles,
				ReadOnlySharedStringsTable strings, int cols, PrintStream target) {
			this.stylesTable = styles;
			this.sharedStringsTable = strings;
			this.minColumnCount = cols;
			this.output = target;
			this.value = new StringBuffer();
			this.nextDataType = xssfDataType.NUMBER;
			this.formatter = new DataFormatter();
			record = new String[this.minColumnCount];
			rows.clear();// 每次读取都清空行集合
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see
		 * org.xml.sax.helpers.DefaultHandler#startElement(java.lang.String,
		 * java.lang.String, java.lang.String, org.xml.sax.Attributes)
		 */
		public void startElement(String uri, String localName, String name,
				Attributes attributes) throws MyExcelSheetHeadModeManualException,SAXException {

			String rowIndex = String.valueOf(attributes.getValue("r"));
			if(notReadAllRows && rowIndex.equals(GETSHEETHEADSREADROWS)){
				throw new MyExcelSheetHeadModeManualException("当标志位不读取全部行,设置参数GETSHEETHEADSREADROWS只读到"+GETSHEETHEADSREADROWS+"后这里强行手动抛异常退出后续数据的读取,该异常为正常的异常");
			}
			
			if ("inlineStr".equals(name) || "v".equals(name)) {
				vIsOpen = true;
				// Clear contents cache
				value.setLength(0);
			}
			// c => cell
			else if ("c".equals(name)) {
				// Get the cell reference
				String r = attributes.getValue("r");
				int firstDigit = -1;
				for (int c = 0; c < r.length(); ++c) {
					if (Character.isDigit(r.charAt(c))) {
						firstDigit = c;
						break;
					}
				}
				thisColumn = nameToColumn(r.substring(0, firstDigit));

				// Set up defaults.
				this.nextDataType = xssfDataType.NUMBER;
				this.formatIndex = -1;
				this.formatString = null;
				String cellType = attributes.getValue("t");
				String cellStyleStr = attributes.getValue("s");
				if ("b".equals(cellType))
					nextDataType = xssfDataType.BOOL;
				else if ("e".equals(cellType))
					nextDataType = xssfDataType.ERROR;
				else if ("inlineStr".equals(cellType))
					nextDataType = xssfDataType.INLINESTR;
				else if ("s".equals(cellType))
					nextDataType = xssfDataType.SSTINDEX;
				else if ("str".equals(cellType))
					nextDataType = xssfDataType.FORMULA;
				else if (cellStyleStr != null) {
					// It's a number, but almost certainly one
					// with a special style or format
					int styleIndex = Integer.parseInt(cellStyleStr);
					XSSFCellStyle style = stylesTable.getStyleAt(styleIndex);
					this.formatIndex = style.getDataFormat();
					this.formatString = style.getDataFormatString();
					if (this.formatString == null)
						this.formatString = BuiltinFormats
								.getBuiltinFormat(this.formatIndex);
				}
			}

		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see org.xml.sax.helpers.DefaultHandler#endElement(java.lang.String,
		 * java.lang.String, java.lang.String)
		 */
		public void endElement(String uri, String localName, String name)
				throws SAXException {

			String thisStr = null;

			// v => contents of a cell
			if ("v".equals(name)) {
				// Process the value contents as required.
				// Do now, as characters() may be called more than once
				switch (nextDataType) {

				case BOOL:
					char first = value.charAt(0);
					thisStr = first == '0' ? "FALSE" : "TRUE";
					break;

				case ERROR:
					thisStr = "\"ERROR:" + value.toString() + '"';
					break;

				case FORMULA:
					// A formula could result in a string value,
					// so always add double-quote characters.
					thisStr = '"' + value.toString() + '"';
					break;

				case INLINESTR:
					// TODO: have seen an example of this, so it's untested.
					XSSFRichTextString rtsi = new XSSFRichTextString(
							value.toString());
					thisStr = '"' + rtsi.toString() + '"';
					break;

				case SSTINDEX:
					String sstIndex = value.toString();
					try {
						int idx = Integer.parseInt(sstIndex);
						XSSFRichTextString rtss = new XSSFRichTextString(
								sharedStringsTable.getEntryAt(idx));
						thisStr = '"' + rtss.toString() + '"';
					} catch (NumberFormatException ex) {
						output.println("Failed to parse SST index '" + sstIndex
								+ "': " + ex.toString());
					}
					break;

				case NUMBER:
					String n = value.toString();
					// 判断是否是日期格式
					if (HSSFDateUtil.isADateFormat(this.formatIndex, n)) {
						Double d = Double.parseDouble(n);
						Date date = HSSFDateUtil.getJavaDate(d);
						thisStr = formateDateToString(date);
					} else if (this.formatString != null)
						thisStr = formatter.formatRawCellContents(
								Double.parseDouble(n), this.formatIndex,
								this.formatString);
					else
						thisStr = n;
					break;

				default:
					thisStr = "(TODO: Unexpected type: " + nextDataType + ")";
					break;
				}

				// Output after we've seen the string contents
				// Emit commas for any fields that were missing on this row
				if (lastColumnNumber == -1) {
					lastColumnNumber = 0;
				}
				// 判断单元格的值是否为空
				if (thisStr == null || "".equals(isCellNull)) {
					isCellNull = true;// 设置单元格是否为空值
				}
				record[thisColumn] = thisStr;
				// Update column
				if (thisColumn > -1)
					lastColumnNumber = thisColumn;

			} else if ("row".equals(name)) {

				// Print out any missing commas if needed
				if (minColumns > 0) {
					// Columns are 0 based
					if (lastColumnNumber == -1) {
						lastColumnNumber = 0;
					}
					if ((isCellNull == false && record[0] != null)|(isCellNull == false && record[1] != null))// 判断是否空行
					{
						rows.add(record.clone());
						isCellNull = false;
						for (int i = 0; i < record.length; i++) {
							record[i] = null;
						}
					}
				}
				lastColumnNumber = -1;
			}

		}

		public List<String[]> getRows() {
			return rows;
		}

		public void setRows(List<String[]> rows) {
			this.rows = rows;
		}

		/**
		 * Captures characters only if a suitable element is open. Originally
		 * was just "v"; extended for inlineStr also.
		 */
		public void characters(char[] ch, int start, int length)
				throws SAXException {
			if (vIsOpen)
				value.append(ch, start, length);
		}

		/**
		 * Converts an Excel column name like "C" to a zero-based index.
		 * 
		 * @param name
		 * @return Index corresponding to the specified name
		 */
		private int nameToColumn(String name) {
			int column = -1;
			for (int i = 0; i < name.length(); ++i) {
				int c = name.charAt(i);
				column = (column + 1) * 26 + c - 'A';
			}
			return column;
		}

		private String formateDateToString(Date date) {
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");// 格式化日期
			return sdf.format(date);

		}

	}

	// /////////////////////////////////////

	private OPCPackage xlsxPackage;
	private int minColumns;
	private PrintStream output;
	private String sheetName;
	private static boolean notReadAllRows;
	private static final String GETSHEETHEADSREADROWS = "A202";//表示只读取到A202单元格,实际数据为200条
	/**
	 * Creates a new XLSX -> CSV converter
	 * 
	 * @param pkg
	 *            The XLSX package to process
	 * @param output
	 *            The PrintStream to output the CSV to
	 * @param minColumns
	 *            The minimum number of columns to output, or -1 for no minimum
	 */
	public XlsXReaderEngine(OPCPackage pkg, PrintStream output,
			String sheetName, int minColumns) {
		this.xlsxPackage = pkg;
		this.output = output;
		this.minColumns = minColumns;
		this.sheetName = sheetName;
	}

	public XlsXReaderEngine() {
	}

	/**
	 * Parses and shows the content of one sheet using the specified styles and
	 * shared-strings tables.
	 * 
	 * @param styles
	 * @param strings
	 * @param sheetInputStream
	 */
	public List<String[]> processSheet(StylesTable styles,
			ReadOnlySharedStringsTable strings, InputStream sheetInputStream)
			throws IOException, ParserConfigurationException, SAXException {

		InputSource sheetSource = new InputSource(sheetInputStream);
		SAXParserFactory saxFactory = SAXParserFactory.newInstance();
		SAXParser saxParser = saxFactory.newSAXParser();
		XMLReader sheetParser = saxParser.getXMLReader();
		MyXSSFSheetHandler handler = new MyXSSFSheetHandler(styles, strings,
				this.minColumns, this.output);
		sheetParser.setContentHandler(handler);
		try {
			sheetParser.parse(sheetSource);
		} catch (MyExcelSheetHeadModeManualException e) {//手动强制退出读取表数据的异常捕获位置
			
			sheetInputStream.close();//关闭输入流
			System.out.println(" sheetParser.parse(sheetSource)按参数GETSHEETHEADSREADROWS只读到"+GETSHEETHEADSREADROWS+"后这里手动抛异常退出后续数据的读取,该异常为正常的异常,输入流已关闭");
			//e.printStackTrace();
		}
		return handler.getRows();
	}

	/**
	 * 初始化这个处理程序 将
	 * 
	 * @throws IOException
	 * @throws OpenXML4JException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	public List<String[]> process() throws IOException, OpenXML4JException,
			ParserConfigurationException, SAXException {

		ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(
				this.xlsxPackage);
		XSSFReader xssfReader = new XSSFReader(this.xlsxPackage);
		List<String[]> list = null;
		StylesTable styles = xssfReader.getStylesTable();

		// 读取 sheet内容
		XSSFReader.SheetIterator iter = (XSSFReader.SheetIterator) xssfReader
				.getSheetsData();
		int index = 0;
		while (iter.hasNext()) {
			InputStream stream = iter.next();
			String sheetNameTemp = iter.getSheetName();
			if (this.sheetName!=null&&this.sheetName.equals(sheetNameTemp)) {
				list = processSheet(styles, strings, stream);
				stream.close();
				++index;
			}
		}

		return list;
	}

	/**
	 * 获取.xlsx 的sheetName
	 * 
	 * @return
	 * @throws IOException
	 * @throws OpenXML4JException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	public List<String> processHeader(String filePath,Integer  minColumns) throws IOException, OpenXML4JException,
			ParserConfigurationException, SAXException {
       List<String> list =new ArrayList<String>();
		ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(
				this.xlsxPackage);
		XSSFReader xssfReader = new XSSFReader(this.xlsxPackage);

		StylesTable styles = xssfReader.getStylesTable();

		// 读取 sheet内容
		XSSFReader.SheetIterator iter = (XSSFReader.SheetIterator) xssfReader
				.getSheetsData();
		while (iter.hasNext()) {
		
			InputStream stream = iter.next();
			String sheetNameTemp = iter.getSheetName();
			notReadAllRows = true;//打开只读取表头的标志
			try {
				if (sheetNameTemp!=null&&sheetNameTemp.length()>0) {
					 List<String[]> data= getSheetDatas(filePath, sheetNameTemp,
								minColumns) ;
					 if(data.size()>0){
						 list.add(sheetNameTemp);
					 }
				
					stream.close();
				}
			} catch (Exception e) {
				notReadAllRows = false;//关闭只读取表头的标志
				e.printStackTrace();
			}
			notReadAllRows = false;//关闭只读取表头的标志
		}


		return list;
	}

	/**
	 * 读取.xlsx文件的数据 以数组集合形式返回
	 * 
	 * @param path
	 *            文件路径
	 * @param sheetName
	 *            sheet名称
	 * @param minColumns
	 *            列总数
	 * @return
	 * @throws SAXException
	 * @throws ParserConfigurationException
	 * @throws OpenXML4JException
	 * @throws IOException
	 */
	public static List<String[]> getSheetDatas(String path, String sheetName,
			int minColumns) throws IOException, OpenXML4JException,
			ParserConfigurationException, SAXException {
		OPCPackage p = OPCPackage.open(path, PackageAccess.READ);

		XlsXReaderEngine xlsx2csv = new XlsXReaderEngine(p, System.out,
				sheetName, minColumns);
		List<String[]> list = xlsx2csv.process();
		p.close();
		return list;
	}

/**
 * 获取不包括表头的数据
 * @param path
 * @param sheetName
 * @param minColumns
 * @param flag      当flag的值为"normal"时，读取整个表，任何其他空字符或非空字符串则读取到GETSHEETHEADSREADROWS 设置好的位置
 * @return
 * @throws IOException
 * @throws OpenXML4JException
 * @throws ParserConfigurationException
 * @throws SAXException
 */
	public static List<String[]> getSheetUseDatas(String path,
			String sheetName, int minColumns,String flag) throws IOException,
			OpenXML4JException, ParserConfigurationException, SAXException {
		OPCPackage p = OPCPackage.open(path, PackageAccess.READ);
		XlsXReaderEngine xlsx2csv = new XlsXReaderEngine(p, System.out,
				sheetName, minColumns);
		if(!flag.equals("normal")){
	    	notReadAllRows=true;//设置需要读取截断，只读取部分行
	    }
		List<String[]> list = xlsx2csv.process();
		    notReadAllRows=false;//关闭只读部分
		p.close();

		return list.subList(1, list.size());
	}

	/**
	 * 读取excel文件xlsx的sheet工作表名称
	 * 
	 * @param filePath
	 * @return
	 * @throws SAXException
	 * @throws ParserConfigurationException
	 * @throws OpenXML4JException
	 * @throws IOException
	 */
	public List<String> getSheetNames(String filePath,Integer minColumns) throws IOException,
			OpenXML4JException, ParserConfigurationException, SAXException {

		OPCPackage p = OPCPackage.open(filePath, PackageAccess.READ);

		XlsXReaderEngine xlsx2csv = new XlsXReaderEngine(p, System.out,
				"", 0);
		List<String> list = xlsx2csv.processHeader(filePath, minColumns);
		p.close();
		return list;
		// 注释的方法 总是堆溢出 不适用了
		// try {
		// InputStream stream = new FileInputStream(filePath);
		// XSSFWorkbook workBook = new XSSFWorkbook(stream);
		// int c = workBook.getNumberOfSheets();
		// for (int i = 0; i < c; i++) {
		// sheetNames.add(workBook.getSheetAt(i).getSheetName());
		// }
		// return sheetNames;
		// } catch (FileNotFoundException e) {
		// // TODO Auto-generated catch block
		// e.printStackTrace();
		// return null;
		// } catch (IOException e) {
		// // TODO Auto-generated catch block
		// e.printStackTrace();
		// return null;
		// }

	}

	/**
	 * 获取.xlsx文件sheet的表头，headCountLimit表示读取每行的多少列
	 * 
	 * @param filePath
	 * @param sheetName
	 * @param headCountLimit
	 * @return
	 */
	public List<String> getSheetHead(String filePath, String sheetName,
			Integer headCountLimit) {
		try {
			notReadAllRows = true;//打开只读取表头的标志
			List<String[]> list = getSheetDatas(filePath, sheetName,
					headCountLimit);
			notReadAllRows = false;//关闭只读取表头的标志
			List<String> heads = new ArrayList<String>();
			for (String head : list.get(0)) {
				if (head != null) {
					heads.add(head.replace("\"", ""));
				}
			}
			return heads;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		} catch (OpenXML4JException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		} catch (ParserConfigurationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		} catch (SAXException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * 以map方式获取sheet表中的数据
	 * 
	 * @param heads
	 * @param datas
	 * @return
	 */
	public List<Map<String, String>> getSheetMaps(List<String> heads,
			List<String[]> datas) {
		// 返回值 map 集合
		List<Map<String, String>> maps = new ArrayList<Map<String, String>>();
		Map map = null;
		for (String[] data : datas) {
			map = new HashMap<String, String>();
			for (int i = 0; i < data.length; i++) {
				map.put(heads.get(i), data[i]);
			}
			maps.add(map);
		}

		return maps;

	}

	/**
	 * 以DBObject方式获取sheet表中的数据
	 * 
	 * @param heads
	 * @param datas
	 * @return
	 */
	public List<DBObject> getSheetDBObjects(List<String> heads,
			List<String[]> datas) {
		// 返回值 DBObject 集合
		List<DBObject> dbobjects = new ArrayList<DBObject>();
		DBObject d = null;
		for (String[] data : datas) {
			d = new BasicDBObject();
			for (int i = 0; i < data.length; i++) {
				d.put(heads.get(i), data[i]);
			}
			dbobjects.add(d);
		}
		return dbobjects;
	}

	public static void main(String[] args) throws Exception {
		// createExcel();

	}

}
