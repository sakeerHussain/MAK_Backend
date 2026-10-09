package com.vehiclerental.mak.invoices;

import java.awt.Color;
import java.io.*;
import java.math.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.stereotype.Service;
import com.vehiclerental.mak.common.ApiException;
import org.springframework.http.HttpStatus;

/** Generates a server-owned, paginated PDF; no browser-supplied HTML is rendered. */
@Service
public class InvoicePdfService {
    private static final BigDecimal HUNDRED=new BigDecimal("100");
    public byte[] render(Map<String,Object> invoice) {
        try (PDDocument doc=new PDDocument(); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            doc.getDocumentInformation().setTitle(s(invoice.get("invoiceNumber")));
            try (var fontStream=PDType0Font.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                if (fontStream==null) throw new IOException("Invoice font unavailable");
                PDFont font=PDType0Font.load(doc,fontStream);
                Canvas c=new Canvas(doc,font,s(invoice.get("invoiceNumber")),"Draft".equals(invoice.get("status")));
                c.section("Invoice metadata");
                c.pairs(new String[][]{{"Invoice #",s(invoice.get("invoiceNumber")).isBlank()?"DRAFT-"+invoice.get("id"):s(invoice.get("invoiceNumber"))},
                    {"Issue date",s(invoice.get("issuedAt")).split("T")[0]},{"Due date",s(invoice.get("dueDate"))},
                    {"Terms",s(invoice.get("paymentTerms"))},{"P.O. #",s(invoice.get("poNumber"))},
                    {"Transaction type",s(invoice.get("transactionType"))}});
                c.section("Seller and buyer details");
                Map<String,Object> seller=map(invoice.get("issuer")), buyer=map(invoice.get("billTo"));
                String[] keys={"name","buildingNumber","street","additionalNumber","district","city","country","postalCode","phone","taxNumber","otherId"};
                String[] labels={"Name","Building number","Street","Additional number","District","City","Country","ZIP / Postal code","Phone","VAT number","Other ID"};
                c.tableHeader(new String[]{"Detail","Seller","Buyer"},new float[]{105,203,203});
                for(int i=0;i<keys.length;i++) c.row(new String[]{labels[i],party(seller,keys[i]),party(buyer,keys[i])},new float[]{105,203,203},false);
                c.row(new String[]{"Billing email","",s(buyer.get("email"))},new float[]{105,203,203},false);
                c.section("Linked bookings / trips");
                for(var source:rows(invoice.get("sources"))) c.text(s(source.get("reference"))+" | Service date: "+s(source.get("serviceDate")),10);
                c.section("Line items - SAR");
                float[] widths={23,149,57,36,69,40,60,77};
                String[] headers={"#","Nature of goods / services","Unit price","Qty","Taxable","VAT %","VAT","Total incl. VAT"};
                c.tableHeader(headers,widths);
                BigDecimal taxable=BigDecimal.ZERO,taxTotal=BigDecimal.ZERO;
                TreeMap<BigDecimal,BigDecimal[]> taxes=new TreeMap<>();int index=0;
                for(var line:rows(invoice.get("lines"))) {
                    BigDecimal gross=n(line.get("quantity")).multiply(n(line.get("unitPrice"))).setScale(2,RoundingMode.HALF_UP);
                    BigDecimal net=gross.subtract(gross.multiply(n(line.get("discountPercent"))).divide(HUNDRED,2,RoundingMode.HALF_UP));
                    BigDecimal rate=n(line.get("taxPercent"));BigDecimal tax=net.multiply(rate).divide(HUNDRED,2,RoundingMode.HALF_UP);
                    taxable=taxable.add(net); taxTotal=taxTotal.add(tax);
                    BigDecimal[] group=taxes.computeIfAbsent(rate,k->new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO});group[0]=group[0].add(net);group[1]=group[1].add(tax);
                    String description=s(line.get("description"))+"\n"+s(line.get("sourceRef"))+" | "+s(line.get("unit"));
                    if(n(line.get("discountPercent")).signum()!=0) description+=" | Discount "+s(line.get("discountPercent"))+"%";
                    c.row(new String[]{""+(++index),description,money(n(line.get("unitPrice"))),s(line.get("quantity")),money(net),rate.stripTrailingZeros().toPlainString()+"%",money(tax),money(net.add(tax))},widths,false);
                }
                c.clearTable();c.ensure(170);c.section("Invoice totals");
                BigDecimal total=taxable.add(taxTotal),paid=BigDecimal.ZERO;
                for(var payment:rows(invoice.get("payments"))) paid=paid.add(n(payment.get("amount")).multiply("Refund".equals(payment.get("kind"))?BigDecimal.valueOf(-1):BigDecimal.ONE));
                c.pairs(new String[][]{{"Total amount (SAR)",money(total)},{"Total taxable amount excluding VAT (SAR)",money(taxable)},
                    {"Total VAT (SAR)",money(taxTotal)},{"Total amount due (SAR)",money(total)},{"Net amount received (SAR)",money(paid)},
                    {"Balance due (SAR)",money(total.subtract(paid).max(BigDecimal.ZERO))}});
                c.section("Tax summary");c.tableHeader(new String[]{"Tax rate","Taxable amount (SAR)","Tax amount (SAR)"},new float[]{111,200,200});
                for(var entry:taxes.entrySet()) c.row(new String[]{entry.getKey().stripTrailingZeros().toPlainString()+"%",money(entry.getValue()[0]),money(entry.getValue()[1])},new float[]{111,200,200},false);
                c.row(new String[]{"Total",money(taxable),money(taxTotal)},new float[]{111,200,200},false);c.clearTable();
                c.section("Notes / Message");c.text(s(invoice.get("notes")),10);
                c.section("Payment details");c.text("Contact person: "+s(invoice.getOrDefault("paymentContact",buyer.get("contactName"))),10);
                c.ensure(170);c.section("Account details");Map<String,Object> account=map(invoice.get("account"));
                c.pairs(new String[][]{{"Account name",s(account.get("accountName"))},{"Bank name",s(account.get("bankName"))},
                    {"Branch name",s(account.get("branchName"))},{"Account number",s(account.get("accountNumber"))},{"IBAN No.",s(account.get("iban"))}});
                c.finish();doc.save(output);return output.toByteArray();
            }
        } catch(IOException e) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,"Unable to generate the invoice PDF. Please retry."); }
    }
    private static String party(Map<String,Object> p,String key) {
        Object v=p.get(key); if(v==null && key.equals("street"))v=p.get("address");if(v==null && key.equals("otherId"))v=p.get("registrationNumber");return s(v);
    }
    private static String s(Object value){return value==null?"":String.valueOf(value);}
    private static BigDecimal n(Object value){return value==null||s(value).isBlank()?BigDecimal.ZERO:new BigDecimal(s(value));}
    private static String money(BigDecimal n){return n.setScale(2,RoundingMode.HALF_UP).toPlainString();}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object v){return v instanceof Map<?,?>?(Map<String,Object>)v:Map.of();}
    @SuppressWarnings("unchecked") private static java.util.List<Map<String,Object>> rows(Object v){return v instanceof java.util.List<?>?(java.util.List<Map<String,Object>>)v:java.util.List.of();}

    private static class Canvas {
        final PDDocument doc;final PDFont font;final String reference;final boolean draft;
        PDPageContentStream stream;float y;int page;String[] tableHeaders;float[] tableWidths;
        Canvas(PDDocument doc,PDFont font,String reference,boolean draft)throws IOException{this.doc=doc;this.font=font;this.reference=reference;this.draft=draft;newPage();}
        void newPage()throws IOException{
            if(stream!=null)stream.close();PDPage p=new PDPage(PDRectangle.A4);doc.addPage(p);page++;
            stream=new PDPageContentStream(doc,p);y=770;
            draw("RENTAL ADMIN",42,805,11,new Color(117,109,232));
            draw(draft?"DRAFT INVOICE":"INVOICE",42,777,20,new Color(53,65,83));
            draw(reference,370,795,10,new Color(53,65,83));
            stream.setStrokingColor(new Color(117,109,232));stream.moveTo(42,761);stream.lineTo(553,761);stream.stroke();y=742;
            draw("Generated by Rental Admin | SAR | Page "+page,42,25,8,new Color(120,130,145));
        }
        void ensure(float height)throws IOException{if(y-height<49){newPage();if(tableHeaders!=null)row(tableHeaders,tableWidths,true);}}
        void clearTable(){tableHeaders=null;tableWidths=null;}
        void section(String title)throws IOException{clearTable();ensure(50);y-=12;draw(title.toUpperCase(Locale.ROOT),42,y,11,new Color(90,85,174));y-=19;}
        void tableHeader(String[] headers,float[] widths)throws IOException{clearTable();ensure(40);row(headers,widths,true);tableHeaders=headers;tableWidths=widths;}
        void pairs(String[][] pairs)throws IOException{for(var p:pairs)row(p,new float[]{255,256},false);clearTable();}
        void text(String value,float size)throws IOException{
            for(String line:wrap(value.isBlank()?"-":value,511,size)){ensure(15);draw(line,42,y,size,new Color(53,65,83));y-=15;}
        }
        void row(String[] values,float[] widths,boolean header)throws IOException{
            float size=header?8:9,leading=12;java.util.List<java.util.List<String>> wrapped=new ArrayList<>();int count=1;
            for(int i=0;i<values.length;i++){var lines=wrap(values[i].isBlank()?"-":values[i],widths[i]-12,size);wrapped.add(lines);count=Math.max(count,lines.size());}
            if (count*leading+8 <= 620) ensure(count*leading+8);
            // Split unusually long cells across pages rather than overflowing the page.
            int offset=0;
            while(offset<count){ensure(leading+8);int fit=Math.max(1,(int)((y-49-8)/leading));int take=Math.min(count-offset,fit);float height=take*leading+8;
                if(header){stream.setNonStrokingColor(new Color(244,243,252));stream.addRect(42,y-height+3,511,height);stream.fill();}
                float x=42;
                for(int col=0;col<values.length;col++){var lines=wrapped.get(col);for(int line=0;line<take && offset+line<lines.size();line++)draw(lines.get(offset+line),x+6,y-8-line*leading,size,new Color(53,65,83));x+=widths[col];}
                y-=height;stream.setStrokingColor(new Color(231,235,241));stream.moveTo(42,y+3);stream.lineTo(553,y+3);stream.stroke();offset+=take;
                if(offset<count){newPage();if(tableHeaders!=null)row(tableHeaders,tableWidths,true);}
            }
        }
        java.util.List<String> wrap(String value,float width,float size)throws IOException{
            java.util.List<String> lines=new ArrayList<>();
            for(String paragraph:value.replace("\r","").split("\n",-1)){
                String line="";
                for(String word:paragraph.split("\\s+")){
                    String next=line.isEmpty()?word:line+" "+word;
                    if(measure(next,size)<=width){line=next;continue;}
                    if(!line.isEmpty()){lines.add(line);line="";}
                    for(int cp:word.codePoints().toArray()){String ch=new String(Character.toChars(cp));if(measure(line+ch,size)>width&&!line.isEmpty()){lines.add(line);line="";}line+=ch;}
                }
                lines.add(line);
            }return lines;
        }
        String supported(String text){StringBuilder out=new StringBuilder();for(int cp:text.codePoints().toArray()){String ch=new String(Character.toChars(cp));try{font.encode(ch);out.append(ch);}catch(Exception e){out.append('?');}}return out.toString();}
        float measure(String text,float size)throws IOException{return font.getStringWidth(supported(text))*size/1000;}
        void draw(String text,float x,float baseline,float size,Color color)throws IOException{stream.beginText();stream.setFont(font,size);stream.setNonStrokingColor(color);stream.newLineAtOffset(x,baseline);stream.showText(supported(text));stream.endText();}
        void finish()throws IOException{stream.close();}
    }
}
