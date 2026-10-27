package com.vehiclerental.mak.payroll;

import com.vehiclerental.mak.common.exception.ApiException;
import java.awt.Color;
import java.io.*;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import static com.vehiclerental.mak.payroll.PayrollPayslipService.*;

/** Server-owned A4 payslip. Salary is approved; payment status is a dated ledger snapshot. */
@Service
public class PayrollPayslipPdf {
    private static final Color INK=new Color(19,47,66),BLUE=new Color(0,126,179),MUTED=new Color(96,117,133),LINE=new Color(220,231,238),PALE=new Color(239,247,251);
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd MMM uuuu",Locale.ENGLISH);
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("dd MMM uuuu, HH:mm 'KSA'",Locale.ENGLISH).withZone(ZoneId.of("Asia/Riyadh"));
    public byte[] render(Payslip slip) {
        try(var document=new PDDocument();var output=new ByteArrayOutputStream();var fontFile=PDType0Font.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            if(fontFile==null)throw new IOException("Payslip font is unavailable.");
            var font=PDType0Font.load(document,fontFile);
            document.getDocumentInformation().setTitle("Payslip "+slip.reference());document.getDocumentInformation().setAuthor(slip.employer().get("name"));
            var page=new Canvas(document,font,slip);
            page.pair("Employee",slip.name());
            page.pair("Staff ID / type",slip.staffNumber()+" / "+("DRIVER".equals(slip.approval().salary().staffType())?"Driver":"Employee"));
            page.pair("Payroll month",YearMonth.parse(slip.period()).format(DateTimeFormatter.ofPattern("MMMM uuuu",Locale.ENGLISH)));
            page.status();
            page.section("CASH EARNINGS");page.header(new String[]{"Salary component","Amount (SAR)"},new float[]{395,120});
            page.row(new String[]{"Basic salary",money(slip.approval().salary().basicSalary())});
            for(var item:slip.approval().salary().components())if(!"BENEFIT".equals(item.settlement()))page.row(new String[]{item.name(),money(item.amount())});
            for(var item:slip.approval().adjustments())if("ADDITION".equals(item.kind())&&!"BENEFIT".equals(item.settlement()))page.row(new String[]{item.name()+" (monthly addition)",money(item.amount())});
            page.total("Total cash earnings",slip.approval().totals().cashEarnings());
            page.section("DEDUCTIONS");page.header(new String[]{"Deduction","Amount (SAR)"},new float[]{395,120});
            boolean deductions=false;for(var item:slip.approval().adjustments())if("DEDUCTION".equals(item.kind())){deductions=true;page.row(new String[]{item.name(),money(item.amount())});}
            if(!deductions)page.row(new String[]{"No deductions",money(BigDecimal.ZERO)});
            page.total("Total deductions",slip.approval().totals().deductions());
            page.section("COMPANY-PROVIDED BENEFITS");page.header(new String[]{"Noncash benefit - excluded from cash payable","Value (SAR)"},new float[]{395,120});
            boolean benefits=false;for(var item:slip.approval().salary().components())if("BENEFIT".equals(item.settlement())){benefits=true;page.row(new String[]{item.name(),money(item.amount())});}
            for(var item:slip.approval().adjustments())if("ADDITION".equals(item.kind())&&"BENEFIT".equals(item.settlement())){benefits=true;page.row(new String[]{item.name()+" (monthly benefit)",money(item.amount())});}
            if(!benefits)page.row(new String[]{"No noncash benefits recorded",money(BigDecimal.ZERO)});
            page.total("Total noncash benefits",slip.approval().totals().benefits());
            page.section("SALARY AND PAYMENT SUMMARY");
            page.summary("Approved net cash entitlement",slip.approval().totals().netCash(),BLUE);
            page.summary("Bank payments recorded",slip.bankPaid(),INK);page.summary("Cash payments recorded",slip.cashPaid(),INK);
            page.summary("Total payments recorded",slip.paid(),INK);page.summary("Outstanding cash salary",slip.outstanding(),slip.outstanding().signum()>0?new Color(149,96,18):new Color(28,109,73));
            page.note("Fixed bank allocation (reference only): SAR "+money(slip.approval().salary().fixedBankAmount())+". This is part of salary, not a deduction or proof of payment.");
            page.section("PAYMENT RECORDS");
            if(slip.payments().isEmpty())page.note(slip.outstanding().signum()>0?"No bank or cash payments have been recorded. Outstanding cash salary remains payable.":"No bank or cash payments have been recorded. No cash payment is due.");
            else {
                page.header(new String[]{"Payment date","Method / status","Reference","Amount (SAR)"},new float[]{80,105,225,105});
                for(var item:slip.payments())page.row(new String[]{DATE.format(item.paidOn()),("BANK".equals(item.method())?"Bank":"Cash")+(item.reversed()?" / Reversed":" / Recorded"),item.reference(),money(item.amount())});
                if(slip.payments().stream().anyMatch(Payment::reversed))page.note("Reversed entries are retained for history and excluded from all payment totals.");
            }
            page.clearTable();page.ensure(43);page.note("Approved: "+STAMP.format(slip.approvedAt())+" by "+slip.approvedBy()+".");
            page.note("Payment position as of "+STAMP.format(slip.generatedAt())+". This payslip records salary and payments; it does not transfer funds or confirm employee acknowledgement.");
            page.finish();document.save(output);return output.toByteArray();
        } catch(IOException|IllegalArgumentException ex) {throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,"The payslip PDF could not be generated. Please retry or check the payroll text fields.");}
    }
    private static String money(BigDecimal value){return value.setScale(2,RoundingMode.UNNECESSARY).toPlainString();}

    private static class Canvas {
        private static final float LEFT=40,RIGHT=555,WIDTH=515,BOTTOM=48;
        final PDDocument document;final PDFont font;final Payslip slip;
        PDPageContentStream stream;float y;String[] headers;float[] widths;
        Canvas(PDDocument document,PDFont font,Payslip slip)throws IOException{this.document=document;this.font=font;this.slip=slip;newPage();}
        void newPage()throws IOException{
            if(stream!=null)stream.close();var page=new PDPage(PDRectangle.A4);document.addPage(page);stream=new PDPageContentStream(document,page);
            draw("MAK INTERNATIONAL",LEFT,806,12.5f,BLUE);right("PAYSLIP",RIGHT,805,19,INK);right(slip.reference(),RIGHT,787,8,INK);
            float bottom=785;for(String line:wrap(slip.employer().get("name"),330,9.2f)){draw(line,LEFT,bottom,9.2f,INK);bottom-=12;}
            String address=join(slip.employer().get("buildingNumber"),slip.employer().get("street"),slip.employer().get("district"),slip.employer().get("city"),slip.employer().get("country"),slip.employer().get("postalCode"));
            for(String line:wrap(address,WIDTH,8)){draw(line,LEFT,bottom,8,MUTED);bottom-=10;}
            if(!slip.employer().get("phone").isBlank()){draw("Phone: "+slip.employer().get("phone"),LEFT,bottom,8,MUTED);bottom-=10;}
            y=Math.min(750,bottom-5);line(y);y-=17;
        }
        void ensure(float height)throws IOException{if(y-height<BOTTOM){var h=headers;var w=widths;newPage();if(h!=null)row(h,w,true);}}
        void section(String title)throws IOException{clearTable();ensure(48);y-=7;draw(title,LEFT,y,9.5f,BLUE);y-=17;}
        void pair(String label,String value)throws IOException{
            var lines=wrap(value,WIDTH-125,9);ensure(lines.size()*11+5);draw(label,LEFT,y,8.7f,MUTED);for(String valueLine:lines){draw(valueLine,LEFT+125,y,9,INK);y-=11;}y-=4;
        }
        void status()throws IOException{
            clearTable();ensure(37);boolean due=slip.outstanding().signum()>0;Color background=due?new Color(255,247,229):new Color(235,248,240),foreground=due?new Color(133,87,16):new Color(29,104,68);
            stream.setNonStrokingColor(background);stream.addRect(LEFT,y-25,WIDTH,30);stream.fill();draw(slip.paymentStatus(),LEFT+10,y-9,10,foreground);right("Outstanding: SAR "+money(slip.outstanding()),RIGHT-10,y-9,10,foreground);y-=35;
        }
        void header(String[] values,float[] columns)throws IOException{headers=values.clone();widths=columns.clone();row(values,columns,true);}
        void row(String[] values)throws IOException{row(values,widths,false);}
        void row(String[] values,float[] columns,boolean header)throws IOException{
            float size=header?8.4f:9,leading=11.5f;var wrapped=new ArrayList<List<String>>();int count=1;
            for(int i=0;i<values.length;i++){var lines=wrap(values[i],columns[i]-12,size);wrapped.add(lines);count=Math.max(count,lines.size());}
            if(!header && count*leading+9<600)ensure(count*leading+9);
            int offset=0;
            while(offset<count){ensure(leading+9);int fit=Math.max(1,(int)((y-BOTTOM-9)/leading)),take=Math.min(count-offset,fit);float height=take*leading+9;
                if(header){stream.setNonStrokingColor(PALE);stream.addRect(LEFT,y-height+3,WIDTH,height);stream.fill();}
                float x=LEFT;
                for(int col=0;col<values.length;col++){var lines=wrapped.get(col);for(int n=0;n<take&&offset+n<lines.size();n++){String value=lines.get(offset+n);if(col==values.length-1)right(value,x+columns[col]-6,y-10-n*leading,size,INK);else draw(value,x+6,y-10-n*leading,size,header?BLUE:INK);}x+=columns[col];}
                y-=height;line(y+3);offset+=take;
                if(offset<count){var savedHeaders=headers;var savedWidths=widths;newPage();if(savedHeaders!=null&&!header)row(savedHeaders,savedWidths,true);}
            }
        }
        void clearTable(){headers=null;widths=null;}
        void total(String label,BigDecimal amount)throws IOException{clearTable();ensure(24);draw(label,LEFT+6,y-9,9,INK);right(money(amount),RIGHT-6,y-9,9.5f,BLUE);y-=24;}
        void summary(String label,BigDecimal amount,Color color)throws IOException{ensure(21);draw(label,LEFT+6,y-8,9,INK);right("SAR "+money(amount),RIGHT-6,y-8,10,color);y-=21;}
        void note(String text)throws IOException{clearTable();for(String value:wrap(text,WIDTH,8)){ensure(11);draw(value,LEFT,y-5,8,MUTED);y-=11;}y-=3;}
        void line(float baseline)throws IOException{stream.setStrokingColor(LINE);stream.moveTo(LEFT,baseline);stream.lineTo(RIGHT,baseline);stream.stroke();}
        void draw(String value,float x,float baseline,float size,Color color)throws IOException{stream.beginText();stream.setFont(font,size);stream.setNonStrokingColor(color);stream.newLineAtOffset(x,baseline);stream.showText(clean(value));stream.endText();}
        void right(String value,float x,float baseline,float size,Color color)throws IOException{draw(value,x-measure(value,size),baseline,size,color);}
        float measure(String value,float size)throws IOException{return font.getStringWidth(clean(value))*size/1000;}
        List<String> wrap(String value,float width,float size)throws IOException{
            var lines=new ArrayList<String>();StringBuilder line=new StringBuilder();
            for(String paragraph:clean(value).split("\n",-1)){
                for(String word:paragraph.split("\\s+")){
                    String next=line.isEmpty()?word:line+" "+word;
                    if(measure(next,size)<=width){line.setLength(0);line.append(next);continue;}
                    if(!line.isEmpty()){lines.add(line.toString());line.setLength(0);}
                    for(int cp:word.codePoints().toArray()){String ch=new String(Character.toChars(cp));if(measure(line+ch,size)>width&&!line.isEmpty()){lines.add(line.toString());line.setLength(0);}line.append(ch);}
                }
                lines.add(line.toString());line.setLength(0);
            }
            return lines;
        }
        static String clean(String value){return Objects.toString(value,"").replace('\u00a0',' ').replace('\t',' ').replace("\r","").replaceAll("[\\p{Cntrl}&&[^\\n]]","");}
        static String join(String... values){return String.join(", ",Arrays.stream(values).filter(v->v!=null&&!v.isBlank()).distinct().toList());}
        void finish()throws IOException{
            stream.close();int pages=document.getNumberOfPages();
            for(int i=0;i<pages;i++)try(var footer=new PDPageContentStream(document,document.getPage(i),PDPageContentStream.AppendMode.APPEND,true,true)){
                stream=footer;line(40);draw("Salary in SAR | Payroll revision "+slip.version(),LEFT,27,7.5f,MUTED);right("Page "+(i+1)+" of "+pages,RIGHT,27,7.5f,MUTED);
            }
        }
    }
}
