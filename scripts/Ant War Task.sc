#########################################
#-- Set script version number
#   Last updated:
#    JAG - 02.11.05 - fixs
#    JAG - 12.08.04 - eliminate copylocal step
#    JAG - 07.13.04 - make 'build.xml' more meaningful
my $ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Ant\040War\040Task.sc,v 1.15 2010/04/13 00:19:54 steve Exp $';
#-- Clean up $ScriptVersion so that it prints useful information
if ($ScriptVersion =~ /^\s*\$Header:\s*(\S+),v\s+(\S+)\s+(\S+)\s+(\S+)/ )
{
 my $path = $1;
 my $version = $2;
 my $date = $3;
 my $time = $4;
 
 #-- massage path
 $path =~ s/\\040/ /g;
 my @t = split /\//, $path;
 my $file = pop @t;
 my $module = $t[2];
 
 $ScriptVersion = "$module, v$version";
}

#########################################
#-- Load Openmake Variables from Data File
#   Uncomment Openmake objects that need to be loaded

#  @PerlData = $AllDeps->load("AllDeps", @PerlData );
@PerlData = $RelDeps->load("RelDeps", @PerlData );
#  @PerlData = $NewerDeps->load("NewerDeps", @PerlData );
@PerlData = $TargetDeps->load("TargetDeps", @PerlData );
@PerlData = $TargetRelDeps->load("TargetRelDeps", @PerlData );
#  @PerlData = $TargetSrcDeps->load("TargetSrcDeps", @PerlData );

$StepDescription = "War File Creation for " . $Target->get;
# 5637 SAB - Verify that dependencies can be found
@DepSearchFound = ();
@DepSearchMissing = ();
@DepList = unique($TargetDeps->getList());

foreach $dep (@DepList){
 unless (-f $dep) {
  push(@DepSearchMissing, "  $dep\n");
 }
}

if (@DepSearchMissing) {
  $RC = 1;
  push(@CompilerOut, "ERROR: Dependencies could not be found\n", "\n");
  push(@CompilerOut, "MISSING DEPENDENCIES:\n", @DepSearchMissing, "\n");
  push @dellist, $Target->get;

  omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArgs,$ClassPathNL,$RC,@CompilerOut) if ($RC == 0);
  omlogger("Final",$StepDescription,"ERROR:","$StepDescription failed.",$Compiler,$CompilerArgs,$ClassPathNL,$RC,@CompilerOut) if ($RC == 1);

  ExitScript $RC;
}

$Verbose = $Quiet =~ /no/i;
$LogOutputNL = "";
my $cwd = &getcwd();
my @IncludesLines = ();
my $uses_zip = 1; #-- all tasks use zipfilesets

if ($ENV{'ARCHIVA_URL'} ne "")
{
 $RC = RuntimeDependencies($ENV{'ARCHIVA_URL'},$ENV{'ARCHIVA_USERID'},$ENV{'ARCHIVA_PASSWORD'},$ENV{'ARCHIVA_REPO'},$RelDeps->getExtList(qw(.jar .zip)));
 
 push(@CompilerOut,"\n");

  omlogger("Final",$StepDescription,"ERROR:","$StepDescription failed.",$Compiler,$CompilerArgs,$ClassPathNL,$RC,@CompilerOut), ExitScript $RC if ($RC == 1);
}


#########################################
#-- Define global variables
#
#-- get the name from the $BuildTask variable
my $Ant_Type = "jar";
my $Task = "";
if ( $BuildTask =~ /Ant.?\s+(\w+)$/ )
{
 $Ant_Type = $1;
 $Ant_Type = lc($Ant_Type);
}

#-- the following are files that might be specified
#   as an option, where the script will have to
#   parse out and substitute a file.
my %TaskOptionFiles = (
                       "manifest" => 1,
                       "webxml"   => 1
                      );

#-- define an array of the "Results From" dependency extensions
#   that need to be parsed for sub task dependencies. Each
#   extension added to the array will be scanned.
my @sub_task_exts = qw(
		       .javac
		       .rmic
		       .jup
		      );


#-- set the name of the build.xml file. This is used
#   for debug purposes to have a more meaningful name
#   when the -ks option is used.
my $Build_XML_File = 'build_' . $Target->get;
$Build_XML_File =~ s|\W+|_|g;
$Build_XML_File .= '.xml';
push @dellist, $Build_XML_File unless $KeepScript =~ /y/i;
my $CompilerArgs .= ' -buildfile ' . $Build_XML_File;

#########################################
#-- determine the configuration
#
my $optionref = $ReleaseFlags;
$optionref = $DebugFlags if ( $CFG eq "DEBUG" );

#########################################
#-- create the option objects
#
use Openmake::BuildOption;
my $buildopt = Openmake::BuildOption->new($optionref);

#########################################
#-- determine how many Build tasks/Option Groups we have
my @build_tasks = $buildopt->getBuildTasks;
my $build_task = $build_tasks[0];

#-- find the build task that matches to the task
$build_task = $BuildTask if ( grep /$BuildTask/, @build_tasks );

#-- find the optiongroups;
my @option_groups = $buildopt->getOptionGroups($build_task);

#-- find the compiler
$Compiler = &GetAnt();

#########################################
#-- Generate Bill of Materials if Requested
#
&GenerateBillofMat($BillOfMaterialFile->get,$BillOfMaterialRpt,$Target->get) if( defined $BillOfMaterialFile );

#########################################
# Special Options section
#
#-- the following is a loop over possible keys in the task argument
#   that we might want to find in the Build Task general options
#   For example, in Ant War, we look for Manifest.mf, web.xml
#   in the list of dependencies
#
#   We define a hash %TaskOptionFiles (at top of script)
#   to tell us what to look for
#   Note that the default type (eg. war) is defined from the
#   build type
#
foreach my $wantedkey ( keys %TaskOptionFiles)
{
 my $wantedfile = $buildopt->getBuildTaskOption( $wantedkey, $build_task );
 $wantedfile =~ s|\\|\/|g;
 $wantedfile =~ s|^"||; #"
 $wantedfile =~ s|"$||; #"
 my $wantedmatch = quotemeta($wantedfile);

 my @foundfiles = ();
 #-- find files that match to the extension, then match to the
 #   full file name
 my $wantedmatchext = "\\.NOEXT";
 if ( $wantedfile =~ /(\.[^\.]*)\s*$/ )
 {
  $wantedmatchext =  $1;
 }
 @foundfiles = $TargetDeps->getExt($wantedmatchext);

 foreach my $foundfile ( @foundfiles )
 {
  $foundfile =~ s|\\|\/|g;
  if ( $foundfile =~ /$wantedmatch$/ && -e $foundfile )
  {
   #-- take the first-found
   $foundfile =~ s|\$|\$\$|g;
   $TaskOptionFiles{$wantedkey} = $foundfile;
   $LogOutputNL .= "\nFound Build Task requested $wantedkey file:\n\n $foundfile\n\n";
   last;
  }
 }
}

##########################################
#
#-- Set the IntDir

$IntDirDL = $IntDir->get . "/";

##########################################
#
#-- Determine the overall basedir

my $BaseDir = $buildopt->getBuildTaskOption( "basedir", $build_task, $OPTIONGROUP_DEFAULT_NAME );
$BaseDir =~ s/^"//; #"
$BaseDir =~ s/"$//; #"

#-- Create the BaseDir with the Intermediate Directory appended as well
my $IntBaseDir = $BaseDir;
if($IntDirDL ne './' && $IntDirDL ne '/')
{
 $IntBaseDir = $IntDirDL . $BaseDir;
}

my $Target_Path = $Target->getDP;
$Target_Path =~ s/\\/\//g;
$Target_Path =~ s/\/$//;
$Target_Path = "" if ( $Target_Path eq "." );

##########################################
#
#-- each Option Group is a separate zipfile set
#
my $i = 0;
foreach my $option_group ( @option_groups )
{
 #-- the "dir" key is a special case that we always look for.
 #   In the future, we will replace this without needing a copy local
 #
 my $wanted_dir = $buildopt->getBuildTaskOption( "dir", $build_task, $option_group) || $BaseDir;
 $wanted_dir =~ s/^"//;
 $wanted_dir =~ s/"$//;
 my $dir_list = "";
 my @wanted_dirs = ();
 my %wanted_file_hash = ();
 my @derived_deps = ();
 my @subtask_deps = ();

 #-- get the files in localres that correspond to this option group
 #-- JAG 04.14.04 - remove 1 as last option -- was returning all files (not
 #                  just files for this build)
 my ($file_ref, $opt_ref ) = $buildopt->getBuildTaskFiles( $build_task, $option_group);
 my @option_files = @{$file_ref};


 #-- remove files that are keyed in %TaskOptionFiles
 my $i = 0;
 my @t_option_files = @option_files;
 foreach my $file ( @option_files )
 {
  foreach my $val ( values %TaskOptionFiles )
  {
   #-- assume lowercase is okay here
   if ( lc $val eq (lc $file) )
   {
    splice( @t_option_files, $i, 1);
    $i--;
   }
  }
  $i++;
 }
 @option_files = @t_option_files;

 ####################################################################################################
 #-- Retrieve all the sub task deps for each matching extension from @sub_task_exts defined at top
 #   Use GetSubTaskDeps to resolve all of the derived dependencies
 #   Also return $dir_list - the list of relative dirs where the matching sup task deps are found ADG 3.15.05
 foreach $sub_task_ext (@sub_task_exts)
 {
  if (grep /$sub_task_ext$/, @option_files)
  {
   my ($rel_dir, @ret_subtask_deps) = &GetSubTaskDeps($TargetDeps,$TargetRelDeps,$wanted_dir,$sub_task_ext);
   push @subtask_deps, @ret_subtask_deps;
   #-- remove the sub task extension files from the @option_files
   @option_files = grep { $_ !~ /$sub_task_ext$/ } @option_files;
   $dir_list .= "$rel_dir," if ($rel_dir);
  }
 }

 if ($Verbose && @subtask_deps != "") #only log if subtask_deps exist
 {
  $LogOutputNL .= "\nIncluding from Sub-Task Dependencies:\n\n";
  foreach (@subtask_deps) { $LogOutputNL .= " $_\n" if $_ =~ /\w+/ ; } #Added to strip out empty strings from logging AG 081804
 }
 
 #-- JAG - add package classes to TargetRelDeps so that we can use TargetRelDeps later
 #         in AntFilesetOrg
 foreach ( @subtask_deps ) { $TargetRelDeps->push($_); }
 
 push @option_files, @subtask_deps; #join the found subtask deps with the exisiting option files
 foreach (@option_files) { $_ =~ s|\\|/|g; } #all slashes forward
 next unless ( @option_files );
 
 #-- we pass in the prefix option, in case we need to split on that
 #
 my $prefix =  $buildopt->getBuildTaskOption( "prefix", $build_task, $option_group );
 $prefix =~ s|\\|\/|g;
 $prefix =~ s|^"||; #"
 $prefix =~ s|"$||; #"

 #-- JAG - 02.11.05 - add intdir to list of possible dirs
 $dir_list .= "$wanted_dir,$IntDirDL,$IntBaseDir";

 $int_dir = $IntDir->getAbsolute;

 my $option_target = Openmake::FileList->new(@option_files);
 %wanted_file_hash = &AntFilesetOrg($option_target,$TargetRelDeps,$dir_list,$int_dir,$prefix,qw( .MF .mf .javac .classpath ));

 push @wanted_dirs, (keys %wanted_file_hash);

 #-- strip off the leading ${dir} off the resources if necessary
 #   this replaces module dir.
 foreach my $temp_dir ( @wanted_dirs )
 {
  if ( $temp_dir)
  {
   $temp_dir =~ s|^"||; #"
   $temp_dir =~ s|"$||; #"

   $temp_dir =~ s|^\.[\\\/]||;

   my $etemp_dir = $temp_dir;
   $etemp_dir =~ s|\\|\/|g;
   $etemp_dir =~ s|\/|\\\/|g;
  }

  #-- write build.xml lines for resources
  if (@{$wanted_file_hash{$temp_dir}} )
  {
   #-- get the options at the Option Group. Ignore the "Default case", since
   #   this will be handled at the root level
   my @options = $buildopt->getBuildTaskOptions( $build_task, $option_group);

   #---Create Custom Manifest for Jar
   # The following section allows for custom lines to be added into the MANIFEST.MF file.
   # The lines to be written in the manifest must be defined in the options for the Ant Jar task
   # The option must be entered with "MFName=" prepended to the value for Option Name and "MFValue=" prepended to the value for Parameter
 
   #---grep out options that include MFName and assign them to an array separate from @options array.
   @MFOptions = (grep /MFName=/, @options);
   #---if @MFOptions defined, and we have not iterated through this if statement yet, 
   # parse values for MFName and MFValue from @MFOptions and assign them
   if (@MFOptions && $UsesManifestParams !~ "1")
   {
    @lines = grep (/MFName=/, @MFOptions);
    foreach $line(@lines)
    {
     @values = split(MFValue,$line);
     $mfname = shift(@values);
     $mfvalue = shift(@values);
     $mfname  =~ s|^.*\=||;
     $mfvalue =~ s|\=||;
   #---format correctly for ANT
     $mfline = "attribute name=" . "\"$mfname\"" . " value=" . "\"$mfvalue\" \/"; 
     push (@mflines, $mfline);
    }
   #---create @ManifestLines array separate from @IncludesLines array
   # @ManifestLines included in Write build.XML below
    push(@ManifestLines, "manifest\n");
    foreach $manifestline(@mflines)
    {
     push(@ManifestLines, "$manifestline\n");
    }
    push(@ManifestLines, "/manifest\n");
    $UsesManifestParams = "1";
   }
   #---end of Create Custom Manifest section

   
   #-- need to parse options for missing quotes
   foreach my $opt ( @options )
   {
    next unless $opt;

    #-- ignore the dir option as specified in the options if
    #   we've redefined it locally in this loop. Check for IntDir as
    #   well
    #
    if ( $opt =~ /(\w*dir)\s*=(.+)/ )
    {
     my $t_opt  = $1;
     my $t_end  = $2;
     $t_end =~ s/^"//;
     $t_end =~ s/"$//;
     $opt = $t_opt . "=\"$temp_dir\"";
       next;
    }

    #-- following gets rid of ill-defined options like manifest=
    if ( $opt =~ /=(")?$/ ) #"
    {
     $opt = "";
     next;
    }
    next if ( $opt =~ /="/ && $opt =~ /"$/); #-- if it's quoted, it's fine.
    $opt =~ s|=|="|; #"
    $opt .= "\"";
   }
   $options = join " ", @options;
   $options =~ s|\s+| |g;

   #-- reset the options if this is the root level. They get set
   #   below.
   if ( $option_group eq $Openmake::BuildOption::OPTIONGROUP_DEFAULT_NAME )
   {
    $options = "";
    if ( $temp_dir ne "." && $temp_dir ne $IntBaseDir)
    {
     $options = "dir=\"$temp_dir\"";
    }
   }

   #-- if $temp_dir is "." and there's a $Target_Path ...
   my $output_dir = $temp_dir;
   $output_dir = $Target_Path . "/" if ( $temp_dir eq "." );

   if ($Quiet =~ /no/i)
   {
    $LogOutputNL .= "\nAdding to $Ant_Type from Directory:";
    $LogOutputNL .= "\n$temp_dir\n\n";
    foreach (@{$wanted_file_hash{$temp_dir}}) { $LogOutputNL .= " $_\n"; }
   }
   #-- Determine if we created a separate zipfileset within the Build Option
   #   Group. If so, add "zipfileset"
   #   Otherwise, add the code under the root level (<jar>, etc)
   #
   if  ( $option_group ne $Openmake::BuildOption::OPTIONGROUP_DEFAULT_NAME
         || $output_dir ne $IntBaseDir || $uses_zip )
   {
    if ( $options !~ /dir=/ )
    {
     if ( $output_dir && ( $output_dir ne $IntBaseDir || $uses_zip ))
     {
      $options .= " dir=\"$output_dir\" ";
     }
     else
     {
      $options .= " dir=\"\${$Ant_Type}\" ";
     }
    }

    push(@IncludesLines, "zipfileset $options\n");
    push(@IncludesLines, GetAntIncludeXML( @{$wanted_file_hash{$temp_dir}} ) );
    push(@IncludesLines, "/zipfileset\n");
    $uses_zip = 1;
   }
   else
   {
    push(@IncludesLines, GetAntIncludeXML( @{$wanted_file_hash{$temp_dir}} ) );
   }
  }
 } #-- end of loop on possible separate directories in the dir= parameter
} #-- end of zipfilesets.

##################################################
# Write Build.xml
#
my $xml =<<ENDXML;
project name = "$Project" default = "$Ant_Type" basedir= "."

 !-- Set properties --
 !--   ignore the classpath that Ant is running under --
 property name = "build.sysclasspath" value = "ignore" /

 property name = "$Ant_Type" value = "." /

 !-- Start $Ant_Type section --
  target name = "$Ant_Type"
ENDXML
$xml .= "  $Ant_Type destfile = \"$E{$Target->get}\" ";

#-- add in Task Level options, parsing out the pieces that we may have
#   replaced
my @options = $buildopt->getBuildTaskOptions($build_task);
@options = (grep {$_ !~ /MFName=/} @options); #want to remove manifest specific options from the general jar options

#-- if there are no options, we may need to add in a basedir
if ( ( ! grep /basedir=/, @options ) && scalar @option_groups == 1 )
{
 my $dir = $IntBaseDir || ".";
 push @options, "basedir=\"$dir\"";
}
foreach my $opt ( @options )
{
 my ( $key, $val);
 if ( $opt =~ /(\w+)="?(.+)"?/ )
 {
  $key = $1;
  $val = $2;
  $val =~ s/"$//; #"
 }
 if ( $TaskOptionFiles{$key} )
 {
  $xml .= $key . "=\"" . $TaskOptionFiles{$key} . "\" " if ( $TaskOptionFiles{$key} != 1 );
 }
 elsif( $key eq "basedir" )
 {
  if( $val =~ /,/)
  {
   #-- ignore basedir if it's comma separated. Do not set to "." otherwise
   #   everything in the basedir gets zipped
   next;
  }
  else
  {
   #-- this is the case where all the files live under the root <jar/war/ear>
   #   task. Here we have to set basedir.
   if ( $IntBaseDir )
   {
    $xml .= "$key=\"$IntBaseDir\" " unless ( $uses_zip );
   }
   elsif ( scalar @option_groups == 1 )
   {
    $xml .= "$key=\"$val\" " unless ($uses_zip) ;
   }
  }
 }
 elsif ( $key && $val )
 {
  $xml .= "$key=\"$val\" ";
 }
}
$xml .= "\n";

$xml .=<<ENDXML2;
   
   @ManifestLines
   @IncludesLines

  /$Ant_Type
 /target

!-- End the project --
/project
ENDXML2

WriteAntXML($xml, $Build_XML_File);

######################################
# Execute Build
#
&omlogger("Begin",$StepDescription,"FAILED","$StepDescription succeeded.",$Compiler,$CompilerArgs,$LogOutputNL,$RC,@Output);

push(@CompilerOut,`$Compiler $CompilerArgs 2>&1`);

$RC = Check4Errors("FAILED",$?,@CompilerOut);

if ( $RC != 0 ) {
 chmod 0777, $Target->get;
 push @dellist, $Target->get;
 
}

&omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), $RC = 1 if ($RC != 0);
&omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut) if ($RC == 0);

#########################################
#-- Generate Footprint if Requested
#
#   For Java, we create a BOM report with the
#   defined name "META-INF/openmake_footprint.log"
#   This gets jarred into the archive
#
#-- Also jar in BOM, but also
#   jar in omXXXXXX.fp file with -0 option (no compression)
#   test with omident.exe
#
if (defined $FootPrintFile)
{
 my $target = $Target->get;
 my $fp_file = $FootPrintFile->get;
 my $bom_fp_file = "META-INF/openmake/openmake_footprint.log";

 my $StepDescription = "Footprint for " . $target;
 my $Compiler = "";
 my @CompilerOut = ();

 mkfulldir( $bom_fp_file);
 #-- JAG - 04.20.06 - if the BOM already exists in text form, use it
 if ( -e $BillOfMaterialRpt )
 {
  my $file = Openmake::File->new($BillOfMaterialRpt)->getDPF();
  $file = $file . '.txt' if ( -e $file . '.txt' );
  $file = $file . '.txt' if ( -e $file . '.log' );
  copy( $file, $bom_fp_file);
 }
 else
 {
  GenerateBillofMat($fp_file, $bom_fp_file, $target);
 }

 #-- everything forward for Java
 $target      =~ s|\\|\/|g;
 $fp_file     =~ s|\\|\/|g;
 $bom_fp_file =~ s|\\|\/|g;

 #-- add the bom
 my @bom_jar = `jar -uf \"$target\" \"$bom_fp_file\" 2>&1`;
 my $RC = $?;
 if ( $RC != 0 )
 {
  push @CompilerOut, @bom_jar;
 }
 push @dellist, $bom_fp_file unless $KeepScript =~ /y/i;

 #-- add the Footprint file. Copy local
 my @t = split "/", $fp_file;
 my $rel_fp_file = pop @t;
 $rel_fp_file = "META-INF/openmake/". $rel_fp_file;

 #-- format the .fp file in the format expected by omident, with $OMBOM, etc
 GenerateFootPrint( {
                      'FootPrint' => $fp_file,
                      'FPSource'  => $rel_fp_file,
                      'FPType'    => 'Java',
                      'Compiler'  => 'jar',
                      'CompilerArguments' => "-u0f \"$target\" \"$rel_fp_file\" 2>&1"
                    }
                   );

 &omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), $RC = 1 if ($RC != 0);
 &omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut) if ($RC == 0);

}

sub RuntimeDependencies
{
	my ($server_url,$userid,$password,$repo,@jars) = @_;
    my $tarfile = $Target->get;
	my $use_readkey = eval { require Frontier::Client; };
	$use_readkey = eval { require Frontier::RPC2; };
	$use_readkey = eval { require LWP::Simple; };

	my $coder = Frontier::RPC2->new;
 
 	my $server = Frontier::Client->new('url' => $server_url . "/xmlrpc" );

	my $retcode = 0;
	
	$server->{rq}->authorization_basic($userid, $password);

	foreach my $jar (@jars)
	{
	 next if ($jar !~ /-/);
	 
	 my $artifactId = substr($jar,0,rindex($jar,"-"));
	 
	 my @args = ($artifactId);
	 my $result = $server->call('SearchService.quickSearch',@args);

	 my @res = @{$result};
	 
	 for ($j=0;$j< scalar @res;$j++)
	 {
	  my %info = %{$res[$j]};
	  
	  next if (%info->{repositoryId} !~ /$repo/i);
	  
	  my $foundjar = %info->{artifactId} . "-" . %info->{version} . "." . %info->{type};
	  
	  if ($jar =~ /$foundjar/i)
	  {
	   my @args = ( %info->{groupId}, %info->{artifactId}, $coder->string(%info->{version}));
	   my $result = $server->call('SearchService.getDependencies',@args);
	  
	   @res = @{$result};
	 
	   for ($i=0;$i < scalar @res;$i++)
	   {
	    %info = %{$res[$i]};
	    push @deptree, $res[$i] if (%info->{scope} !~ /test|provide/i);  
	   }
	  }
	 }
	}

	my @SP = $VPath->getList();
	push (@SP,$IntDir->get) if ($IntDir->get ne "." && $IntDir->get ne "");
	
	foreach my $artifact (@deptree)
	{
	 my @args = ($artifact->{artifactId});
	 my $artver = $artifact->{version};
	 
	 my $propErr = 0;
	 if ($artver =~ /\$\{/)
	 {
	  $artver =~ s/\$\{//g;
	  $artver =~ s/\}//g;
	  
	  $retcode = 1, $propErr=1, push(@CompilerOut,"ERROR 210: $artver property definition is not defined\n") if ($ENV{$artver} eq "");
	  $artver = $ENV{$artver};
	 }
	 
	  my $reldepfile = $artifact->{groupId} . "/" . $artifact->{artifactId} . "/" . $artver . "/" . $artifact->{artifactId} . "-" . $artver . "." . $artifact->{type};	  

	  my $foundjaronFS = 0;
	  
	 foreach my $dir (@SP)
	 {
	  my $lookfor = $dir;
	  $lookfor = $cwd if ($dir eq "." || $dir eq "./" || $dir eq ".\\");

	  $lookfor .= "/" . $reldepfile;
	  if ($^O =~ /win32/i)
	  {
	   $lookfor =~ s/\//\\/g;
	  }
	  else
	  {
	   $lookfor =~ s/\\/\//g;
	  }

	  if (-e $lookfor)
      {
	  	my $localjar = Openmake::File->new($lookfor);

       $ReleaseFlags->{$tarfile}->{'Ant War'}->{'Web-Inf Lib'}->{$localjar->get} = 'BTOG(Ant War|Web-Inf Lib)[95]{ prefix=WEB-INF/lib} DT[5]';
	   $DebugFlags->{$tarfile}->{'Ant War'}->{'Web-Inf Lib'}->{$localjar->get} = 'BTOG(Ant War|Web-Inf Lib)[95]{ prefix=WEB-INF/lib} DT[5]';

	   $TargetDeps->push($localjar->get);
	   $TargetRelDeps->push($localjar->getFE);
	   $foundjaronFS = 1;
	   last;
      }
	 }
	 
	 if ($foundjaronFS == 0 && $propErr == 0)
	 {
	  my $localjar = Openmake::File->new(File::Spec->catfile($cwd, $IntDir->get, $reldepfile));

	  $localjar->mkdir();

	  push(@CompilerOut,"Caching $server_url/repository/$repo/$reldepfile -> " . $localjar->get . "\n");
	  
	  getstore( "$server_url/repository/$repo/$reldepfile", $localjar->get );
      
       $ReleaseFlags->{$tarfile}->{'Ant War'}->{'Web-Inf Lib'}->{$localjar->get} = 'BTOG(Ant War|Web-Inf Lib)[95]{ prefix=WEB-INF/lib} DT[5]';
	   $DebugFlags->{$tarfile}->{'Ant War'}->{'Web-Inf Lib'}->{$localjar->get} = 'BTOG(Ant War|Web-Inf Lib)[95]{ prefix=WEB-INF/lib} DT[5]';

	   $TargetDeps->push($localjar->get);
	   $TargetRelDeps->push($localjar->getFE);
	   $TargetRelDeps->push($localjar->getFE);
	 }

	 
	 my $result = $server->call('SearchService.quickSearch',@args);

	 my @res = @{$result};
	 
	 my $found = 0;
	 
	 for ($j=0;$j < scalar @res;$j++)
	 {
	  my %info = %{$res[$j]};

	  next if (%info->{repositoryId} !~ /$repo/i);
	  my $iver = %info->{version};
	 
	  if ($iver =~ /\$\{/)
	  {
	   $iver =~ s/\$\{//g;
	   $iver =~ s/\}//g;
	   $RC = 1, print "ERROR 210: $iver property definition is not defined\n" if ($ENV{$iver} eq "");   
	   $iver = $ENV{$iver};
	  }
	  
	  $artifact->{type} = "jar" if ($artifact->{type} eq "pom");
	  %info->{type} = "jar" if (%info->{type} eq "pom");
	  
	  my $foundjar = %info->{artifactId} . "-" . $iver . "." . %info->{type};
	  my $jar = $artifact->{artifactId} . "-" . $artver . "." . $artifact->{type};
	  
	  if ($jar =~ /$foundjar/i)
	  {
	   my @args = ( %info->{groupId}, %info->{artifactId}, $coder->string($iver));
	   my $result = $server->call('SearchService.getDependencies',@args);
	   $found = 1;
	   @res = @{$result};
	 
	   for ($i=0;$i < scalar @res;$i++)
	   {
	    %info = %{$res[$i]};
	    push @deptree, $res[$i] if (%info->{scope} !~ /test|provide/i);  
	   }
	  }
	 }
	 
	 $retcode=1, push(@CompilerOut,"ERROR 211: GroupId=" . $artifact->{groupId} . " ArtifactId=" . $artifact->{artifactId} . " Version=" . $artver . " is not found in the Archiva Repository Index.  Please add the component to the Repository and Re-index.\n") if ($found eq 0 && $propErr eq 0);
	}
  return $retcode;	
}