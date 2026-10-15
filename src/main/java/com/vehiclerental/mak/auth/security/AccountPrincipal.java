package com.vehiclerental.mak.auth.security;

import java.io.Serializable;

public record AccountPrincipal(long id,long version) implements Serializable {}
