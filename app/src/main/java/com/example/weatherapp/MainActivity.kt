package com.example.weatherapp

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.location.LocationManagerCompat.isLocationEnabled
import com.karumi.dexter.Dexter
import com.karumi.dexter.Dexter.withActivity
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationRequest
import android.location.LocationRequest.Builder
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.renderscript.RenderScript.Priority
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import com.example.weatherapp.databinding.ActivityMainBinding
import com.example.weatherapp.models.Weather
import com.example.weatherapp.models.WeatherResponse
import com.example.weatherapp.network.WeatherService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.gson.Gson
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*


@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {


    private lateinit var mFusedLocationClient: FusedLocationProviderClient

    private var mProgressDialog: Dialog? = null

    lateinit var binding: ActivityMainBinding

    private lateinit var mSharedPreferences: SharedPreferences


    @RequiresApi(Build.VERSION_CODES.N)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding=ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        mSharedPreferences=getSharedPreferences(Constants.PREFERENCE_NAME,Context.MODE_PRIVATE)

        setupUI()

        if (!isLocationEnabled()) {
            Toast.makeText(
                this,
                "Your location is turned OFF, Please turn it ON",
                Toast.LENGTH_SHORT
            ).show()

            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        } else {
            Dexter.withActivity(this).withPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ).withListener(object : MultiplePermissionsListener {

                override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                    if (report!!.areAllPermissionsGranted()) {
                        //Add Request Location Data
                        requestLocationData()
                    }

                    if (report.isAnyPermissionPermanentlyDenied) {
                        Toast.makeText(this@MainActivity,
                            "You have Denied Location Permission, Please enable as mandatory",
                            Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onPermissionRationaleShouldBeShown(
                    p0: MutableList<PermissionRequest>?,
                    p1: PermissionToken?
                ) {
                    showRationalDialogForPermissions()
                }
            }).onSameThread().check()
        }


    }

    private fun isLocationEnabled(): Boolean {
        //Access to the System Location Services

        val locationManager: LocationManager =
            getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }


    private fun showRationalDialogForPermissions() {
        AlertDialog.Builder(this)
            .setMessage("Weather Needs Turn On the Location. Please enable from Settings")
            .setPositiveButton(
                "GO TO SETTINGS"
            ) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = Uri.fromParts("package", packageName, null)
                    intent.data = uri
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    e.printStackTrace()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }.show()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationData() {

        val mLocationRequest = com.google.android.gms.location.LocationRequest()
        mLocationRequest.priority = PRIORITY_HIGH_ACCURACY

        mFusedLocationClient.requestLocationUpdates(
            mLocationRequest, mLocationCallback,
            Looper.myLooper()
        )
    }

    private val mLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val mLastLocation: Location = locationResult.lastLocation
            val latitude = mLastLocation.latitude
            Log.i("Current Latitude", "$latitude")

            val longitude = mLastLocation.longitude
            Log.i("Current Longitude", "$longitude")
            getLocationWeatherDetails(latitude, longitude)
        }
    }

    private fun getLocationWeatherDetails(latitude: Double, longitude: Double) {
        if (Constants.isNetworkAvailable(this)) {
            val retrofit: Retrofit = Retrofit.Builder()
                .baseUrl(Constants.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val service: WeatherService = retrofit.create(WeatherService::class.java)

            val listCall: Call<WeatherResponse> =
                service.getWeather(latitude, longitude, Constants.METRIC_UNIT, Constants.APP_ID)

            showCustomProgressDialog()

            listCall.enqueue(object : Callback<WeatherResponse> {
                @RequiresApi(Build.VERSION_CODES.N)
                override fun onResponse(
                    call: Call<WeatherResponse>,
                    response: Response<WeatherResponse>
                ) {
                    if (response!!.isSuccessful) {

                        hideProgressDialog()
                        val weatherList: WeatherResponse = response.body()!!
                        Log.i("Response Result", "$weatherList")

                        val weatherResponseJsonString= Gson().toJson(weatherList)
                        val editor=mSharedPreferences.edit()
                        editor.putString(Constants.WEATHER_RESPONSE_DATA,weatherResponseJsonString)
                        editor.apply()
                        setupUI()

                    } else {
                        val rc = response.code()
                        when (rc) {
                            400 -> {
                                Log.e("Error 400", "Bad Connection")
                            }
                            404 -> {
                                Log.e("Error 404", "Not Found")
                            }
                            else -> {
                                Log.e("Error", "Generic Error")
                            }
                        }
                    }
                }

                override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {

                    hideProgressDialog()

                    Log.e("Error", t!!.message.toString())

                }

            })
        } else {
            Toast.makeText(this@MainActivity,
                "Internet connection Not Available",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCustomProgressDialog() {
        mProgressDialog = Dialog(this)

        mProgressDialog!!.setContentView(R.layout.dialog_custom_progress)

        mProgressDialog!!.show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main,menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        return when(item.itemId){
            R.id.action_refresh-> {
                requestLocationData()
                true
            }else -> super.onOptionsItemSelected(item)
        }
        return super.onOptionsItemSelected(item)
    }

    private fun hideProgressDialog() {
        if (mProgressDialog != null) {
            mProgressDialog!!.dismiss()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun setupUI(){

        val weatherResponseJsonString=mSharedPreferences.getString(Constants.WEATHER_RESPONSE_DATA,"")

        if(!weatherResponseJsonString.isNullOrEmpty()){
            val weatherList=Gson().fromJson(weatherResponseJsonString,WeatherResponse::class.java)
            for(i in weatherList.weather.indices) {
                Log.i("Weather Name", weatherList.weather.toString())


                binding.tvMainSf.text=weatherList.weather[i].main
                binding.tvMainDescriptionSf.text=weatherList.weather[i].description
                binding.tvMainHm.text=weatherList.main.temp.toString()+ getUnit(application.resources.configuration.locales.toString())

                binding.tvMainSr.text=unixTime(weatherList.sys.sunrise)
                binding.tvMainSs.text=unixTime(weatherList.sys.sunset)

                binding.tvMainDescriptionHm.text=weatherList.main.humidity.toString()+"per cent"
                binding.tvMainMin.text=weatherList.main.temp_min.toString()+"min"
                binding.tvMainMax.text=weatherList.main.temp_max.toString()+"max"
                binding.tvMainWind.text=weatherList.wind.speed.toString()
                binding.tvMainName.text=weatherList.name
                binding.tvMainDescriptionCountry.text=weatherList.sys.country

                /*
                when(weatherList.weather[i].icon{
                "01d"->iv_main.setImageResource(R.drawable.sunny)
                }
                 */
            }
        }

    }
    private fun getUnit(value:String):String?{
        var value="°C"
        if("US"==value || "LR"==value || "MM"==value){
            value="°F"
        }
        return value
    }

    private fun unixTime(timex:Long):String?{
        val date= Date(timex*1000L)
        val sdf=SimpleDateFormat("HH:MM:SS")
        sdf.timeZone= TimeZone.getDefault()
        return sdf.format(date)
    }

}


