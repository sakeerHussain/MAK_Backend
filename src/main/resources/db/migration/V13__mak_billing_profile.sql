create table billing_profile (
 id bigint primary key, version bigint not null, payload text not null,
 updated_at timestamp with time zone not null, updated_by bigint
);
create table billing_profile_versions (
 version bigint primary key, payload text not null,
 updated_at timestamp with time zone not null, updated_by bigint
);
insert into billing_profile(id,version,payload,updated_at) values
 (1,0,'{"issuer":{"name":"Mishary Al Khonaini International Contracting Est.","buildingNumber":"6958","street":"10th Street","additionalNumber":"3598","district":"Al Aqrabiyah","city":"Al Aqrabiyah","country":"Saudi Arabia","postalCode":"31952","phone":"013 8892607","taxNumber":"301151675900003","otherId":""},"account":{"accountName":"Mishary Al Khonaini International Cont. Est","bankName":"SAUDI AWWAL BANK [SAB]","branchName":"Al Aqrabiyah Branch, Al Khobar 34446","accountNumber":"059 - 410266 - 001","iban":"SA0845000000059410266001"},"paymentContact":"Mishary Al Khonaini","paymentInstructions":""}',current_timestamp);
insert into billing_profile_versions(version,payload,updated_at) select version,payload,updated_at from billing_profile where id=1;
