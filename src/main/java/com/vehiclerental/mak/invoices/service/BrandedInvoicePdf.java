package com.vehiclerental.mak.invoices.service;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.*;
import java.util.*;
import java.util.List;
import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.Bidi;
import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.*;
import com.vehiclerental.mak.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Template 3: bilingual, structured, print-safe invoice with server-owned QR data. */
final class BrandedInvoicePdf {
 private final InvoiceTime time;
 BrandedInvoicePdf(InvoiceTime time){this.time=time;}
 static String s(Object v){return v==null?"":v.toString();}
 static BigDecimal n(Object v){return s(v).isBlank()?BigDecimal.ZERO:new BigDecimal(s(v));}
 static String money(BigDecimal v){return String.format(Locale.US,"%,.2f",v);}
 @SuppressWarnings("unchecked") static Map<String,Object> map(Object v){return v instanceof Map<?,?>?(Map<String,Object>)v:Map.of();}
 @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Object v){return v instanceof List<?>?(List<Map<String,Object>>)v:List.of();}
 byte[] render(Map<String,Object> data){
  try(var doc=new PDDocument();var output=new ByteArrayOutputStream();var latin=PDType0Font.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");var arabic=getClass().getResourceAsStream("/invoices/Amiri-Regular.ttf")){
   var c=new Sheet(doc,PDType0Font.load(doc,latin),PDType0Font.load(doc,arabic),data);
   c.header(true);
   c.meta();c.parties();c.items();c.reconciliation();c.bank();c.finish();
   doc.getDocumentInformation().setTitle(s(data.get("invoiceNumber")));doc.save(output);return output.toByteArray();
  }catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,"Unable to generate the invoice PDF. Check invoice details and retry.");}
 }
 private class Sheet {
  final PDDocument doc;final PDFont latin,arabic;final Map<String,Object> data;
  final Color navy=new Color(16,42,67),blue=new Color(0,134,189),pale=new Color(235,246,252),line=new Color(207,222,232);
  PDPageContentStream cs;float y;int page;boolean table;
  BigDecimal taxable=BigDecimal.ZERO,vat=BigDecimal.ZERO;
  final TreeMap<BigDecimal,BigDecimal[]> taxes=new TreeMap<>();
  final float[] columns={20,185,32,61,65,34,60,74};
  Sheet(PDDocument d,PDFont l,PDFont a,Map<String,Object> v){doc=d;latin=l;arabic=a;data=v;}
  boolean draft(){return "Draft".equals(data.get("status"));}
  String ref(){return s(data.get("invoiceNumber")).isBlank()?"DRAFT-"+s(data.get("id")):s(data.get("invoiceNumber"));}
  void header(boolean first)throws Exception {
   if(cs!=null)cs.close();var p=new PDPage(PDRectangle.A4);doc.addPage(p);page++;cs=new PDPageContentStream(doc,p);
   try(var logo=getClass().getResourceAsStream("/mail/mak-logo.jpg")){if(logo!=null)cs.drawImage(PDImageXObject.createFromByteArray(doc,logo.readAllBytes(),"MAK"),32,770,215,57);}
   text(draft()?"DRAFT INVOICE":"Tax Invoice",265,803,18,navy);text(draft()?"مسودة فاتورة":"فاتورة ضريبية",265,778,17,navy);
   text(ref(),32,750,10,navy);
   if(first&&!draft()){
    String payload=s(data.get("phaseOneQr"));if(payload.isBlank())payload=InvoicePhaseOneQr.encode(data,InvoicePhaseOneQr.issueInstant(s(data.get("issuedAt"))));
    var matrix=new QRCodeWriter().encode(payload,BarcodeFormat.QR_CODE,360,360,Map.of(EncodeHintType.MARGIN,4));
    var image=new BufferedImage(360,360,BufferedImage.TYPE_INT_RGB);for(int x=0;x<360;x++)for(int z=0;z<360;z++)image.setRGB(x,z,matrix.get(x,z)?0:0xffffff);
    var qr=LosslessFactory.createFromImage(doc,image);qr.setInterpolate(false);cs.drawImage(qr,476,746,88,88);
   }
   rule(32,738,563,738);y=725;
   text("MAK International  |  SAR",32,23,7,navy);text("Page "+page,520,23,7,navy);
   if(!first){text("Continued · "+ref(),32,720,9,navy);y=698;}
  }
  void ensure(float h)throws Exception {if(y-h<45){header(false);if(table)itemHeader();}}
  void rule(float x,float y1,float right,float y2)throws IOException{cs.setStrokingColor(line);cs.setLineWidth(.5f);cs.moveTo(x,y1);cs.lineTo(right,y2);cs.stroke();}
  void fill(float x,float top,float w,float h,Color color)throws IOException{cs.setNonStrokingColor(color);cs.addRect(x,top-h,w,h);cs.fill();}
  String visual(String value)throws Exception {if(!value.matches("(?s).*[\\u0600-\\u06ff].*"))return value;String shaped=new ArabicShaping(ArabicShaping.LETTERS_SHAPE).shape(value);return new Bidi(shaped,Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).writeReordered(Bidi.DO_MIRRORING);}
  PDFont font(String v){return v.matches("(?s).*[\\u0600-\\u06ff\\ufb50-\\ufeff].*")?arabic:latin;}
  void text(String v,float x,float baseline,float size,Color color)throws Exception {
   if(v==null||v.isBlank())return;String content=visual(v.replaceAll("[\\p{Cntrl}]"," "));PDFont f=font(content);
   // Missing glyphs are rejected instead of silently replacing invoice text with question marks.
   cs.beginText();cs.setFont(f,size);cs.setNonStrokingColor(color);cs.newLineAtOffset(x,baseline);cs.showText(content);cs.endText();
  }
  float width(String v,float size)throws Exception {String value=visual(v);return font(value).getStringWidth(value)*size/1000;}
  List<String> wrap(String value,float max,float size)throws Exception {
   var out=new ArrayList<String>();for(String paragraph:value.replace("\r","").split("\n",-1)){
    String current="";for(String word:paragraph.split("\\s+")){String next=current.isEmpty()?word:current+" "+word;if(width(next,size)<=max){current=next;continue;}if(!current.isEmpty()){out.add(current);current="";}for(int cp:word.codePoints().toArray()){String ch=new String(Character.toChars(cp));if(width(current+ch,size)>max&&!current.isEmpty()){out.add(current);current="";}current+=ch;}}out.add(current);
   }return out;
  }
  String value(Map<String,Object> p,String key){return s(p.getOrDefault(key,key.equals("street")?p.get("address"):key.equals("otherId")?p.get("registrationNumber"):""));}
  void meta()throws Exception {
   String date=s(data.get("issuedAt")).isBlank()?"Not issued":time.day(s(data.get("issuedAt")))+" KSA";
   String[][] pairs={{"Invoice # / رقم الفاتورة",ref(),"Issue date / تاريخ الإصدار",date},{"Due date / الاستحقاق",s(data.get("dueDate")),"Terms / الشروط",s(data.get("paymentTerms"))},{"P.O. # / أمر الشراء",s(data.get("poNumber")),"Transaction / المعاملة",s(data.get("transactionType"))}};
   for(var row:pairs){float h=18;for(int i=0;i<4;i++)h=Math.max(h,wrap(row[i],i%2==0?124:125,7).size()*10+7);ensure(h);for(int i=0;i<4;i++){float x=32+i*132.75f;if(i%2==0)fill(x,y,132.75f,h,pale);var lines=wrap(row[i],124,7);for(int a=0;a<lines.size();a++)text(lines.get(a),x+4,y-11-a*10,7,navy);}y-=h;rule(32,y,563,y);}y-=12;
  }
  void parties()throws Exception {
   Map<String,Object> seller=map(data.get("issuer")),buyer=map(data.get("billTo"));
   String[] keys={"name","buildingNumber","street","additionalNumber","district","city","country","postalCode","phone","taxNumber"};
   String[] labels={"Name / الاسم","Building / المبنى","Street / الشارع","Additional / الإضافي","District / الحي","City / المدينة","Country / الدولة","Postal / البريدي","Phone / الهاتف","VAT / الرقم الضريبي"};
   ensure(190);fill(32,y,260,18,pale);fill(303,y,260,18,pale);text("Seller / البائع",38,y-12,9,navy);text("Buyer / المشتري",309,y-12,9,navy);y-=18;
   for(int i=0;i<keys.length;i++){var a=wrap(value(seller,keys[i]),168,7.5f);var b=wrap(value(buyer,keys[i]),168,7.5f);float h=Math.max(14,Math.max(a.size(),b.size())*10+4);ensure(h);text(labels[i],36,y-10,6.8f,navy);text(labels[i],307,y-10,6.8f,navy);for(int k=0;k<a.size();k++)text(a.get(k),120,y-10-k*10,7.5f,navy);for(int k=0;k<b.size();k++)text(b.get(k),391,y-10-k*10,7.5f,navy);y-=h;rule(32,y,292,y);rule(303,y,563,y);}
   y-=12;
  }
  void itemHeader()throws Exception {
   String[] en={"#","Description","Qty","Unit price","Taxable","VAT %","VAT","Total (SAR)"};
   String[] ar={"رقم","الوصف","الكمية","سعر الوحدة","الخاضع","النسبة","الضريبة","الإجمالي"};
   fill(32,y,531,25,navy);float x=32;for(int i=0;i<en.length;i++){text(en[i],x+3,y-10,6.5f,Color.WHITE);text(ar[i],x+3,y-21,6.5f,Color.WHITE);x+=columns[i];}y-=25;
  }
  void items()throws Exception {
   ensure(70);text("Invoice items / بنود الفاتورة",32,y-10,10,blue);y-=20;table=true;itemHeader();int index=0;
   for(var row:rows(data.get("lines"))){BigDecimal gross=n(row.get("quantity")).multiply(n(row.get("unitPrice"))).setScale(2,RoundingMode.HALF_UP);BigDecimal net=gross.subtract(gross.multiply(n(row.get("discountPercent"))).divide(BigDecimal.valueOf(100),2,RoundingMode.HALF_UP));BigDecimal rate=n(row.get("taxPercent")),tax=net.multiply(rate).divide(BigDecimal.valueOf(100),2,RoundingMode.HALF_UP);taxable=taxable.add(net);vat=vat.add(tax);var group=taxes.computeIfAbsent(rate,k->new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO});group[0]=group[0].add(net);group[1]=group[1].add(tax);
    String description=s(row.get("description"));if(!s(row.get("sourceRef")).isBlank())description+="\n"+s(row.get("sourceRef"));if(!s(row.get("unit")).isBlank())description+=" · "+s(row.get("unit"));if(n(row.get("discountPercent")).signum()!=0)description+="\nDiscount "+s(row.get("discountPercent"))+"%";
    String[] values={""+(++index),description,s(row.get("quantity")),money(n(row.get("unitPrice"))),money(net),rate.stripTrailingZeros().toPlainString()+"%",money(tax),money(net.add(tax))};
    var wrapped=new ArrayList<List<String>>();int count=1;for(int i=0;i<8;i++){var lines=wrap(values[i],columns[i]-7,7);wrapped.add(lines);count=Math.max(count,lines.size());}
    int offset=0;while(offset<count){ensure(20);int take=Math.min(count-offset,Math.max(1,(int)((y-50)/11)));float h=take*11+8;if(h<560)ensure(h);float x=32;for(int col=0;col<8;col++){for(int k=0;k<take&&offset+k<wrapped.get(col).size();k++)text(wrapped.get(col).get(offset+k),x+3,y-11-k*11,7,navy);x+=columns[col];}y-=h;rule(32,y,563,y);offset+=take;}
   }table=false;y-=12;
  }
  float smallRows(String title,List<String[]> entries,float x,float top,float w)throws Exception {
   fill(x,top,w,17,pale);text(title,x+4,top-12,8,navy);float pos=top-17;
   for(var row:entries){var left=wrap(row[0],w*.64f-10,7);var right=wrap(row[1],w*.36f-10,7);float h=Math.max(left.size(),right.size())*10+5;for(int i=0;i<left.size();i++)text(left.get(i),x+4,pos-10-i*10,7,navy);for(int i=0;i<right.size();i++)text(right.get(i),x+w*.64f+4,pos-10-i*10,7,navy);pos-=h;rule(x,pos,x+w,pos);}
   return pos;
  }
  void reconciliation()throws Exception {
   BigDecimal total=taxable.add(vat),paid=BigDecimal.ZERO,credited=n(data.get("creditedAmount"));for(var p:rows(data.get("payments")))paid=paid.add(n(p.get("amount")).multiply("Refund".equals(p.get("kind"))?BigDecimal.valueOf(-1):BigDecimal.ONE));BigDecimal adjusted=total.subtract(credited),balance=adjusted.subtract(paid).max(BigDecimal.ZERO),refund=paid.subtract(adjusted).max(BigDecimal.ZERO);
   var entries=new ArrayList<String[]>();entries.add(new String[]{"Subtotal / المجموع",money(taxable)});entries.add(new String[]{"VAT / الضريبة",money(vat)});entries.add(new String[]{"Invoice total / الإجمالي",money(total)});if(credited.signum()!=0)entries.add(new String[]{"Credit notes",money(credited)});entries.add(new String[]{"Net received / المستلم",money(paid)});entries.add(new String[]{"Balance due / الرصيد",money(balance)});if(refund.signum()>0)entries.add(new String[]{"Refund due",money(refund)});
   var payRows=new ArrayList<String[]>();var allPayments=rows(data.get("payments"));
   for(var p:allPayments.subList(0,Math.min(3,allPayments.size())))payRows.add(new String[]{s(p.get("date"))+" · "+s(p.get("kind"))+" · "+s(p.get("method"))+"\n"+s(p.get("reference")),money(n(p.get("amount")))});
   if(payRows.isEmpty())payRows.add(new String[]{"No payments recorded","—"});
   var taxRows=new ArrayList<String[]>();for(var e:taxes.entrySet())taxRows.add(new String[]{e.getKey().stripTrailingZeros().toPlainString()+"% · Taxable "+money(e.getValue()[0]),money(e.getValue()[1])});
   float required=Math.max(155,entries.size()*25+35);required=Math.max(required,payRows.size()*45+taxRows.size()*25+65);ensure(required);float top=y;
   float rightBottom=smallRows("Invoice totals / إجمالي الفاتورة",entries,303,top,260);
   float leftBottom=smallRows("Payment history / سجل المدفوعات",payRows,32,top,260);
   leftBottom=smallRows("Tax summary / ملخص الضريبة",taxRows,32,leftBottom-12,260);
   if(!draft()){String status=refund.signum()>0?"REFUND DUE":balance.signum()>0?(paid.signum()>0?"PARTIALLY PAID":"UNPAID"):adjusted.signum()==0?(credited.signum()>0?"CREDITED":"NO BALANCE"):"PAID / مدفوع";fill(303,rightBottom-5,260,23,pale);text(status,310,rightBottom-20,11,blue);rightBottom-=30;}
   y=Math.min(leftBottom,rightBottom)-14;
   if(allPayments.size()>3){section("Additional payment history");for(var p:allPayments.subList(3,allPayments.size()))flow(s(p.get("date"))+" | "+s(p.get("kind"))+" | "+s(p.get("method"))+" | "+s(p.get("reference"))+" | SAR "+money(n(p.get("amount"))),8);}
   if(!rows(data.get("creditNotes")).isEmpty()){section("Credit notes");for(var c:rows(data.get("creditNotes")))flow(s(c.get("number"))+" | "+s(c.get("reason"))+" | SAR "+money(n(c.get("total"))),8);}
  }
  void section(String title)throws Exception{ensure(35);fill(32,y,531,17,pale);text(title,36,y-12,9,navy);y-=23;}
  void flow(String text,float size)throws Exception{for(String line:wrap(text,519,size)){ensure(13);text(line,36,y-9,size,navy);y-=13;}}
  void bank()throws Exception {
   Map<String,Object> account=map(data.get("account"));ensure(125);
   var left=List.of(new String[]{"Beneficiary / المستفيد",s(account.get("accountName"))},new String[]{"Bank / البنك",s(account.get("bankName"))},new String[]{"Branch / الفرع",s(account.get("branchName"))});
   var right=List.of(new String[]{"IBAN",s(account.get("iban"))},new String[]{"Account / الحساب",s(account.get("accountNumber"))},new String[]{"Contact / الاتصال",s(data.get("paymentContact"))});
   float leftEnd=smallRows("Bank details / تفاصيل البنك",left,32,y,260),rightEnd=smallRows("Receiving account / حساب المستفيد",right,303,y,260);y=Math.min(leftEnd,rightEnd)-12;
   if(!s(data.get("paymentInstructions")).isBlank())flow(s(data.get("paymentInstructions")),7.5f);
   if(!s(data.get("notes")).isBlank()){section("Notes / ملاحظات");flow(s(data.get("notes")),8);}
   if(!rows(data.get("sources")).isEmpty()){section("Booking references / مراجع الحجز");for(var source:rows(data.get("sources")))flow(s(source.get("reference"))+" | "+s(source.get("serviceDate")),7.5f);}
  }
  void finish()throws IOException {cs.close();}
 }
}
